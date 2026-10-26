package dev.tlong.biodex.data.repo

import androidx.room.withTransaction
import dev.tlong.biodex.data.backup.BackupEntry
import dev.tlong.biodex.data.backup.BackupSnapshot
import dev.tlong.biodex.data.backup.BackupSpecies
import dev.tlong.biodex.data.backup.BackupStore
import dev.tlong.biodex.data.backup.ImportPlan
import dev.tlong.biodex.data.backup.LocalEntry
import dev.tlong.biodex.data.backup.LocalSnapshot
import dev.tlong.biodex.data.catalogue.pairKingdomAndClass
import dev.tlong.biodex.data.db.AppDatabase
import dev.tlong.biodex.data.db.CaptureEntity
import dev.tlong.biodex.data.db.EcosystemEntity
import dev.tlong.biodex.data.db.EntryEntity
import dev.tlong.biodex.data.db.EntryStatusRow
import dev.tlong.biodex.data.db.SpeciesEcosystemCrossRef
import dev.tlong.biodex.data.db.SpeciesEntity
import dev.tlong.biodex.data.db.SpeciesRegionEntity
import dev.tlong.biodex.data.photo.CaptureDeletionPlan
import dev.tlong.biodex.data.photo.CaptureStore
import dev.tlong.biodex.data.photo.PlaceBackfillPlan
import dev.tlong.biodex.data.photo.RegistrationPlan
import dev.tlong.biodex.domain.Capture
import dev.tlong.biodex.domain.DexProgress
import dev.tlong.biodex.domain.DexProgressMath
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Entry
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.Lineage
import dev.tlong.biodex.domain.SpeciesUse
import dev.tlong.biodex.domain.RangeGrid
import dev.tlong.biodex.domain.Region
import dev.tlong.biodex.domain.keptUsesNote
import dev.tlong.biodex.domain.SpeciesDetail
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.USER_DEX_NUMBER_BASE
import dev.tlong.biodex.domain.storedDexNumber
import dev.tlong.biodex.domain.UserSpeciesRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The region a fresh install opens on, and the region of any row written before D94. */
const val DEFAULT_REGION_ID = "pacific"

/**
 * D95: the region picker's All view — every species in every dex. Never a row in `regions`;
 * a species added while it is on screen takes it as its home and joins no regional dex.
 */
const val ALL_REGIONS_ID = "all"
const val ALL_REGIONS_NAME = "All regions"

/**
 * The surface every screen consumes (ARCHITECTURE.md 3.1, 6.3). Slice 3 exposed reads only;
 * slice 5 adds the capture writes as the [CaptureStore] implementation — the user-added write
 * paths are still slice 7's.
 *
 * Search and filtering are deliberately *not* here: section 6.2 puts them in the ViewModel,
 * composed over these cold flows in memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DexRepository(
    private val db: AppDatabase,
    /**
     * D94: the regional dex on screen. Every region-scoped read below switches with it, each
     * through one `flatMapLatest` so a screen never sees one region's species with another's
     * ecosystems.
     */
    private val activeRegion: StateFlow<String> = MutableStateFlow(DEFAULT_REGION_ID),
) : CaptureStore, UserSpeciesStore, BackupStore {

    private val entryStatusFlow: Flow<List<EntryStatusRow>> = db.entryDao().observeEntryStatuses()

    private val membershipFlow: Flow<List<SpeciesEcosystemCrossRef>> =
        db.ecosystemDao().observeAllMemberships()

    /** D95: the species the dex on screen lists, each under its number there. */
    private val dexSpeciesFlow: Flow<List<SpeciesEntity>> = activeRegion.flatMapLatest { regionId ->
        combine(
            db.speciesDao().observeAllSpecies(),
            db.speciesRegionDao().observeAll(),
            db.regionDao().observeRegions(),
        ) { species, listings, regions ->
            dexView(regionId, species, listings, regions.map { it.id })
        }
    }

    private fun ecosystemRows(regionId: String): Flow<List<EcosystemEntity>> =
        if (regionId == ALL_REGIONS_ID) {
            // Region by region, renumbered so a sort by `sortOrder` keeps that order.
            db.ecosystemDao().observeAllEcosystems()
                .map { rows -> rows.mapIndexed { i, row -> row.copy(sortOrder = i + 1) } }
        } else {
            db.ecosystemDao().observeEcosystems(regionId)
        }

    /** D94: every bundled region, in picker order, then D95's All view when there are two. */
    fun regions(): Flow<List<Region>> =
        db.regionDao().observeRegions().map { rows ->
            val regional = rows.map { Region(it.id, it.name) }
            if (regional.size < 2) regional else regional + Region(ALL_REGIONS_ID, ALL_REGIONS_NAME)
        }

    /**
     * D34's shared map outline, decoded once per emission of the region row. Every species'
     * map is drawn over this same grid; only the shading differs.
     */
    fun rangeGrid(): Flow<RangeGrid> = db.regionDao().observeRegions().map { regions ->
        // D95: every region draws the same world grid, so the first one's serves every dex.
        val region = regions.firstOrNull()
        RangeGrid.fromMask(region?.landMask, region?.rangeGridWidth ?: 0, region?.rangeGridHeight ?: 0)
    }

    /** Every species in dex order — curated first, user-added trailing (M01/M02). */
    fun speciesSummaries(): Flow<List<SpeciesSummary>> =
        combine(dexSpeciesFlow, membershipFlow, entryStatusFlow) { species, memberships, statuses ->
            assembleSummaries(species, memberships, statuses)
        }

    fun ecosystems(): Flow<List<Ecosystem>> = activeRegion.flatMapLatest { regionId ->
        ecosystemRows(regionId).map { rows -> rows.map { it.toDomain() } }
    }

    /** Shared by the grid header and the Stats screen (6.3). */
    fun dexProgress(): Flow<DexProgress> = activeRegion.flatMapLatest { regionId ->
        combine(
            dexSpeciesFlow,
            membershipFlow,
            entryStatusFlow,
            ecosystemRows(regionId),
            db.regionDao().observeRegion(regionId),
        ) { species, memberships, statuses, ecosystems, region ->
            val caught = statuses.map { it.speciesId }.toSet()
            DexProgressMath.compute(
                regionId = regionId,
                // Empty until the first import seeds the row; the header simply shows no
                // region pill for those few hundred milliseconds rather than an id.
                regionName = if (regionId == ALL_REGIONS_ID) ALL_REGIONS_NAME else region?.name.orEmpty(),
                species = species.map {
                    DexProgressMath.SpeciesRow(
                        id = it.id,
                        source = it.source,
                        taxClass = it.taxClass,
                        kingdom = it.kingdom,
                        caught = it.id in caught,
                    )
                },
                memberships = memberships.map {
                    DexProgressMath.MembershipRow(it.speciesId, it.ecosystemId)
                },
                ecosystems = ecosystems.map { it.toDomain() },
            )
        }
    }

    /**
     * Numbered as the dex on screen numbers it, or by its home dex when that one does not list
     * it — a detail opened from Recent or Nearest stays whole whichever dex is on screen.
     */
    fun speciesDetail(speciesId: String): Flow<SpeciesDetail?> =
        db.speciesDao().observeSpeciesById(speciesId).flatMapLatest { species ->
            if (species == null) return@flatMapLatest flowOf(null)
            combine(dexSpeciesFlow, membershipFlow, entryStatusFlow) { dex, memberships, statuses ->
                val listed = dex.firstOrNull { it.id == speciesId } ?: species
                SpeciesDetail(
                    summary = assembleSummaries(listOf(listed), memberships, statuses).first(),
                    habitatText = species.habitatText,
                    description = species.description,
                    imageUrl = species.imageUrl,
                    infoUrl = species.infoUrl,
                    imageAttribution = species.imageAttribution,
                    usesNote = species.usesNote,
                    userEditedFields = species.userEditedFields,
                    rangeCells = species.rangeCells,
                )
            }
        }

    fun captures(speciesId: String): Flow<List<Capture>> =
        db.captureDao().observeCaptures(speciesId).map { rows -> rows.map { it.toDomain() } }

    fun recentCaptures(limit: Int = 12): Flow<List<Capture>> =
        db.captureDao().observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    /** D68: the places already on this collection's sightings, most recent first. */
    fun placeLabels(limit: Int = 40): Flow<List<String>> =
        db.captureDao().observePlaceLabels(limit)

    fun entry(speciesId: String): Flow<Entry?> =
        combine(db.entryDao().observeEntry(speciesId), entryStatusFlow) { entry, statuses ->
            entry?.let {
                Entry(
                    speciesId = it.speciesId,
                    caughtAt = it.caughtAt,
                    favoriteCaptureId = it.favoriteCaptureId,
                    captureCount = statuses.firstOrNull { s -> s.speciesId == it.speciesId }
                        ?.captureCount ?: 0,
                    preferOwnPhoto = it.preferOwnPhoto,
                )
            }
        }

    fun capture(captureId: String): Flow<Capture?> =
        db.captureDao().observeCapture(captureId).map { it?.toDomain() }

    // -----------------------------------------------------------------------
    // Writes (slice 5). Every decision behind these calls is a pure function in
    // `data/photo/CapturePlans.kt`; this class only applies plans, in transactions.
    // -----------------------------------------------------------------------

    override suspend fun entryOnce(speciesId: String): Entry? =
        db.entryDao().entryOnce(speciesId)?.let {
            Entry(
                speciesId = it.speciesId,
                caughtAt = it.caughtAt,
                favoriteCaptureId = it.favoriteCaptureId,
                captureCount = db.captureDao().countForSpecies(speciesId),
                preferOwnPhoto = it.preferOwnPhoto,
            )
        }

    override suspend fun captureOnce(captureId: String): Capture? =
        db.captureDao().captureOnce(captureId)?.toDomain()

    override suspend fun capturesForSpecies(speciesId: String): List<Capture> =
        db.captureDao().observeCaptures(speciesId).first().map { it.toDomain() }

    override suspend fun captureCountForUri(photoUri: String): Int =
        db.captureDao().countForUri(photoUri)

    override suspend fun applyRegistration(plan: RegistrationPlan) {
        db.withTransaction {
            // The entry goes in first: `captures` has no FK to `entries`, but writing the
            // capture first would leave a window where the species reads as uncaught.
            plan.newEntry?.let {
                db.entryDao().upsert(
                    EntryEntity(
                        speciesId = it.speciesId,
                        caughtAt = it.caughtAt,
                        favoriteCaptureId = it.favoriteCaptureId,
                    ),
                )
            }
            db.captureDao().insert(plan.capture.toEntity())
            // D75: a photo taken earlier than the first one moves the caught date back.
            db.entryDao().syncCaughtAt(plan.capture.speciesId)
        }
    }

    override suspend fun applyDeletion(plan: CaptureDeletionPlan) {
        db.withTransaction {
            // Nulling first: `entries.favoriteCaptureId` has no foreign key (3.4), so nothing
            // else would stop it dangling at a row that is about to vanish.
            if (plan.clearFavorite) {
                db.entryDao().setFavoriteCapture(plan.speciesId, null)
            }
            db.captureDao().deleteById(plan.captureId)
            if (plan.deleteEntry) {
                db.entryDao().deleteBySpeciesId(plan.speciesId)
            } else {
                db.entryDao().syncCaughtAt(plan.speciesId)
            }
        }
    }

    override suspend fun setFavoriteCapture(speciesId: String, captureId: String?) {
        db.entryDao().setFavoriteCapture(speciesId, captureId)
    }

    override suspend fun setPreferOwnPhoto(speciesId: String, preferOwnPhoto: Boolean) {
        db.entryDao().setPreferOwnPhoto(speciesId, preferOwnPhoto)
    }

    override suspend fun updateCaptureReference(
        captureId: String,
        photoUri: String,
        thumbPath: String,
    ) {
        db.captureDao().updateReference(captureId, photoUri, thumbPath)
    }

    override suspend fun clearCaptureReference(captureId: String) {
        db.captureDao().clearReference(captureId)
    }

    override suspend fun applyPlaceBackfill(plan: PlaceBackfillPlan) {
        db.captureDao().fillPlace(plan.captureId, plan.lat, plan.lng, plan.locationLabel)
    }

    // -----------------------------------------------------------------------
    // User-added species (slice 7). Every decision is in `AddSpeciesRegistrar` and
    // `domain/UserSpecies.kt`; this half only reads and writes rows.
    // -----------------------------------------------------------------------

    override suspend fun maxUserDexNumber(): Int? =
        db.speciesDao().maxUserDexNumber()

    override suspend fun userSpecies(speciesId: String): UserSpeciesRecord? {
        val row = db.speciesDao().speciesOnceById(speciesId) ?: return null
        if (row.source != SpeciesSource.USER) return null
        return row.toUserRecord()
    }

    override suspend fun upsertUserSpecies(record: UserSpeciesRecord, ecosystemIds: List<String>?) {
        db.withTransaction {
            db.speciesDao().upsert(record.toEntity())
            listUserSpecies(record.id, record.regionId, record.dexNumber)
            if (ecosystemIds != null) {
                // Replaced wholesale for this one species: the card's multi-select is the whole
                // truth about its ecosystems, and a null list means "leave them alone" (a
                // backfill never touches D10's manual pick).
                db.ecosystemDao().deleteMembershipsFor(listOf(record.id))
                if (ecosystemIds.isNotEmpty()) {
                    db.ecosystemDao().upsertMemberships(
                        ecosystemIds.distinct().map { SpeciesEcosystemCrossRef(record.id, it) },
                    )
                }
            }
        }
    }

    /** D95: a user species is listed in its home dex, unless it was added in the All view. */
    private suspend fun listUserSpecies(speciesId: String, regionId: String, dexNumber: Int) {
        if (regionId != ALL_REGIONS_ID) {
            db.speciesRegionDao().insertIfAbsent(SpeciesRegionEntity(speciesId, regionId, dexNumber))
        }
    }

    // -----------------------------------------------------------------------
    // Export and import (slice 8, S01). Every rule lives in `data/backup/`; this
    // half reads rows and applies a plan.
    // -----------------------------------------------------------------------

    /** D94: every region's rows, each species carrying its own region id. */
    override suspend fun backupSnapshot(): BackupSnapshot {
        val species = db.speciesDao().allSpeciesOnce()
        val ecosystemsBySpecies = db.ecosystemDao().allMembershipsOnce()
            .groupBy({ it.speciesId }, { it.ecosystemId })
        return BackupSnapshot(
            regionId = DEFAULT_REGION_ID,
            species = species.map { it.toBackup(ecosystemsBySpecies[it.id].orEmpty()) },
            entries = db.entryDao().entriesOnce().map {
                BackupEntry(it.speciesId, it.caughtAt, it.favoriteCaptureId, it.preferOwnPhoto)
            },
            captures = db.captureDao().capturesOnce().map { it.toDomain() },
        )
    }

    override suspend fun localSnapshot(): LocalSnapshot {
        // Across every region: a U-number free in all of them is free in the one the species
        // lands in, so the restore never needs to know which region it is filling.
        val species = db.speciesDao().allSpeciesOnce()
        return LocalSnapshot(
            speciesSources = species.associate { it.id to it.source },
            usedUserDexNumbers = species.filter { it.dexNumber > USER_DEX_NUMBER_BASE }
                .map { it.dexNumber }
                .toSet(),
            captureIds = db.captureDao().captureIdsOnce().toSet(),
            entries = db.entryDao().entriesOnce()
                .associate {
                    it.speciesId to LocalEntry(it.caughtAt, it.favoriteCaptureId, it.preferOwnPhoto)
                },
            ecosystemIds = db.ecosystemDao().allEcosystemsOnce().map { it.id }.toSet(),
        )
    }

    override suspend fun applyImport(plan: ImportPlan) {
        db.withTransaction {
            // Species first: both captures and entries carry a foreign key to it.
            // An archive written before D94 names no region per species; all of it is Pacific.
            plan.speciesToInsert.forEach {
                val regionId = it.regionId ?: DEFAULT_REGION_ID
                db.speciesDao().upsert(it.toEntity(regionId))
                listUserSpecies(it.id, regionId, it.dexNumber)
            }
            plan.memberships.forEach { (speciesId, ecosystemIds) ->
                if (ecosystemIds.isNotEmpty()) {
                    db.ecosystemDao().upsertMemberships(
                        ecosystemIds.map { SpeciesEcosystemCrossRef(speciesId, it) },
                    )
                }
            }
            plan.capturesToInsert.forEach { db.captureDao().upsert(it.capture.toEntity()) }
            plan.entriesToWrite.forEach {
                db.entryDao().upsert(
                    EntryEntity(
                        speciesId = it.speciesId,
                        caughtAt = it.caughtAt,
                        favoriteCaptureId = it.favoriteCaptureId,
                        preferOwnPhoto = it.preferOwnPhoto,
                    ),
                )
            }
            // D75: an archive written before v24 carries registration dates.
            db.entryDao().syncAllCaughtAt()
        }
    }
}

internal fun SpeciesEntity.toBackup(ecosystemIds: List<String>) = BackupSpecies(
    id = id,
    regionId = regionId,
    source = source.wireName,
    dexNumber = dexNumber,
    commonName = commonName,
    taxClass = taxClass.wireName,
    silhouetteRes = silhouetteRes,
    scientificName = scientificName,
    detailsPending = detailsPending,
    habitatText = habitatText,
    description = description,
    imageUrl = imageUrl,
    infoUrl = infoUrl,
    imageAttribution = imageAttribution,
    userEditedFields = userEditedFields,
    ecosystemIds = ecosystemIds,
    kingdom = kingdom.wireName,
    uses = uses,
    usesNote = usesNote,
    lineageKingdom = lineageKingdom,
    lineagePhylum = lineagePhylum,
    lineageClass = lineageClass,
    lineageOrder = lineageOrder,
    lineageFamily = lineageFamily,
)

internal fun BackupSpecies.toEntity(regionId: String) = SpeciesEntity(
    id = id,
    regionId = regionId,
    dexNumber = dexNumber,
    // Only user-added species are ever inserted by an import (`planImport`), so the source
    // is not read back from the archive: a file cannot talk this app into creating a
    // catalogue species the bundled asset does not have.
    source = SpeciesSource.USER,
    detailsPending = detailsPending,
    commonName = commonName,
    scientificName = scientificName,
    taxClass = pairedClass,
    kingdom = pairedKingdom,
    habitatText = habitatText,
    description = description,
    imageUrl = imageUrl,
    infoUrl = infoUrl,
    imageAttribution = imageAttribution,
    silhouetteRes = silhouetteRes,
    userEditedFields = userEditedFields,
    uses = restoredUses,
    // A note with no use behind it is dropped, so a hand-edited archive cannot produce a
    // species whose note has nowhere to render — but a `Caution:` sentence survives with no
    // tags, because a restore must not be the step that quietly loses a recorded toxicity.
    usesNote = keptUsesNote(usesNote, SpeciesUse.setFromWireNames(restoredUses)),
    // D36. Restored as written: an archive is the only record of a user-added species'
    // classification, and dropping it here would leave every restored species outside the
    // hop count until it happened to be opened online again.
    lineageKingdom = lineageKingdom,
    lineagePhylum = lineagePhylum,
    lineageClass = lineageClass,
    lineageOrder = lineageOrder,
    lineageFamily = lineageFamily,
)

/** An archive is a file a user could have edited, so the pairing invariant is re-checked. */
private val BackupSpecies.paired get() = pairKingdomAndClass(kingdom, taxClass)
private val BackupSpecies.pairedKingdom get() = paired.first
private val BackupSpecies.pairedClass get() = paired.second
private val BackupSpecies.restoredUses
    get() = SpeciesUse.setFromWireNames(uses).sortedBy { it.ordinal }.map { it.wireName }

/**
 * The two halves of the user-added row's round trip, and the one place a field can go missing
 * without anything failing.
 *
 * **Every column the confirm card can write must appear in both.** A column read here but not
 * written there survives one save and is wiped by the next re-upsert; a column written but not
 * read comes back as its default on the following backfill and is then written back over the
 * good value. Slice 12 hit exactly that: `toEntity` defaulted the plant columns and set the
 * kingdom from the class, so a re-upsert of a user-added plant silently emptied its uses. The
 * instrumented round-trip test in `UserSpeciesRoomTest` is what pins the pair together.
 */
internal fun SpeciesEntity.toUserRecord() = UserSpeciesRecord(
    id = id,
    regionId = regionId,
    dexNumber = dexNumber,
    detailsPending = detailsPending,
    fields = SpeciesFields(
        commonName = commonName,
        scientificName = scientificName,
        kingdom = kingdom,
        taxClass = taxClass,
        habitatText = habitatText,
        description = description,
        imageUrl = imageUrl,
        imageAttribution = imageAttribution,
        infoUrl = infoUrl,
        uses = SpeciesUse.setFromWireNames(uses),
        usesNote = usesNote,
        lineage = Lineage(
            kingdom = lineageKingdom,
            phylum = lineagePhylum,
            taxonClass = lineageClass,
            order = lineageOrder,
            family = lineageFamily,
        ),
    ),
    userEditedFields = userEditedFields,
)

internal fun UserSpeciesRecord.toEntity() = SpeciesEntity(
    id = id,
    regionId = regionId,
    dexNumber = dexNumber,
    source = SpeciesSource.USER,
    detailsPending = detailsPending,
    commonName = fields.commonName,
    scientificName = fields.scientificName,
    taxClass = fields.taxClass,
    habitatText = fields.habitatText,
    description = fields.description,
    imageUrl = fields.imageUrl,
    infoUrl = fields.infoUrl,
    imageAttribution = fields.imageAttribution,
    silhouetteRes = fields.silhouetteRes,
    userEditedFields = userEditedFields,
    kingdom = fields.kingdom,
    uses = fields.uses.sortedBy { it.ordinal }.map { it.wireName },
    usesNote = fields.usesNote,
    lineageKingdom = fields.lineage.kingdom,
    lineagePhylum = fields.lineage.phylum,
    lineageClass = fields.lineage.taxonClass,
    lineageOrder = fields.lineage.order,
    lineageFamily = fields.lineage.family,
)

internal fun Capture.toEntity() = CaptureEntity(
    id = id,
    speciesId = speciesId,
    photoUri = photoUri,
    thumbPath = thumbPath,
    localCopyPath = localCopyPath,
    takenAt = takenAt,
    lat = lat,
    lng = lng,
    locationLabel = locationLabel,
    note = note,
    createdAt = createdAt,
)

/**
 * D95: the species one dex shows, each carrying its number in that dex. A regional dex is the
 * rows `species_regions` lists for it. The All view is every row, numbered national-dex style:
 * per kingdom, each curated species in the order of the first region (by [regionOrder]) that
 * lists it, then its number there. A user species keeps its U-number, which is global.
 */
internal fun dexView(
    regionId: String,
    species: List<SpeciesEntity>,
    listings: List<SpeciesRegionEntity>,
    regionOrder: List<String>,
): List<SpeciesEntity> {
    if (regionId != ALL_REGIONS_ID) {
        val numbers = listings.filter { it.regionId == regionId }.associate { it.speciesId to it.dexNumber }
        return species.mapNotNull { row -> numbers[row.id]?.let { row.copy(dexNumber = it) } }
            .sortedBy { it.dexNumber }
    }
    val rank = regionOrder.withIndex().associate { (i, id) -> id to i }
    val firstListing = listings.groupBy { it.speciesId }.mapValues { (_, rows) ->
        rows.minWith(compareBy({ rank[it.regionId] ?: Int.MAX_VALUE }, { it.dexNumber }))
    }
    val (curated, user) = species.partition { it.source == SpeciesSource.CURATED }
    val numbered = curated.groupBy { it.kingdom }.flatMap { (kingdom, rows) ->
        rows.sortedWith(
            compareBy(
                { firstListing[it.id]?.let { l -> rank[l.regionId] } ?: Int.MAX_VALUE },
                { firstListing[it.id]?.dexNumber ?: it.dexNumber },
            ),
        ).mapIndexed { i, row -> row.copy(dexNumber = storedDexNumber(kingdom, i + 1)) }
    }
    return (numbered + user).sortedBy { it.dexNumber }
}

/**
 * Grid-cell assembly, kept pure and package-visible so the JVM tests can check the
 * caught/uncaught and thumbnail rules without a device. Room supplies the three row sets;
 * at 120-plus species the join costs nothing in memory (the reasoning of 6.2).
 */
internal fun assembleSummaries(
    species: List<SpeciesEntity>,
    memberships: List<SpeciesEcosystemCrossRef>,
    statuses: List<EntryStatusRow>,
): List<SpeciesSummary> {
    val ecosystemsBySpecies = memberships.groupBy({ it.speciesId }, { it.ecosystemId })
    val statusBySpecies = statuses.associateBy { it.speciesId }
    return species.map { row ->
        val status = statusBySpecies[row.id]
        SpeciesSummary(
            id = row.id,
            regionId = row.regionId,
            dexNumber = row.dexNumber,
            source = row.source,
            detailsPending = row.detailsPending,
            commonName = row.commonName,
            scientificName = row.scientificName,
            taxClass = row.taxClass,
            kingdom = row.kingdom,
            uses = SpeciesUse.setFromWireNames(row.uses),
            silhouetteRes = row.silhouetteRes,
            ecosystemIds = ecosystemsBySpecies[row.id].orEmpty(),
            caughtAt = status?.caughtAt,
            thumbPath = status?.thumbPath,
            // M41: the grid's stand-in for a photo the user did not keep.
            imageUrl = row.imageUrl,
            captureCount = status?.captureCount ?: 0,
            preferOwnPhoto = status?.preferOwnPhoto ?: false,
            // D36: five columns in, one value out, so nothing downstream has to know the
            // path was ever stored flat.
            lineage = Lineage(
                kingdom = row.lineageKingdom,
                phylum = row.lineagePhylum,
                taxonClass = row.lineageClass,
                order = row.lineageOrder,
                family = row.lineageFamily,
            ),
        )
    }
}

internal fun EcosystemEntity.toDomain() = Ecosystem(id, regionId, name, sortOrder)

internal fun CaptureEntity.toDomain() = Capture(
    id = id,
    speciesId = speciesId,
    photoUri = photoUri,
    thumbPath = thumbPath,
    localCopyPath = localCopyPath,
    takenAt = takenAt,
    lat = lat,
    lng = lng,
    locationLabel = locationLabel,
    note = note,
    createdAt = createdAt,
)
