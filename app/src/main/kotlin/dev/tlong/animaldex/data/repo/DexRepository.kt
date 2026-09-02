package dev.tlong.animaldex.data.repo

import androidx.room.withTransaction
import dev.tlong.animaldex.data.backup.BackupEntry
import dev.tlong.animaldex.data.backup.BackupSnapshot
import dev.tlong.animaldex.data.backup.BackupSpecies
import dev.tlong.animaldex.data.backup.BackupStore
import dev.tlong.animaldex.data.backup.ImportPlan
import dev.tlong.animaldex.data.backup.LocalEntry
import dev.tlong.animaldex.data.backup.LocalSnapshot
import dev.tlong.animaldex.data.db.AppDatabase
import dev.tlong.animaldex.data.db.CaptureEntity
import dev.tlong.animaldex.data.db.EcosystemEntity
import dev.tlong.animaldex.data.db.EntryEntity
import dev.tlong.animaldex.data.db.EntryStatusRow
import dev.tlong.animaldex.data.db.SpeciesEcosystemCrossRef
import dev.tlong.animaldex.data.db.SpeciesEntity
import dev.tlong.animaldex.data.photo.CaptureDeletionPlan
import dev.tlong.animaldex.data.photo.CaptureStore
import dev.tlong.animaldex.data.photo.RegistrationPlan
import dev.tlong.animaldex.domain.Capture
import dev.tlong.animaldex.domain.DexProgress
import dev.tlong.animaldex.domain.DexProgressMath
import dev.tlong.animaldex.domain.Ecosystem
import dev.tlong.animaldex.domain.Entry
import dev.tlong.animaldex.domain.SpeciesDetail
import dev.tlong.animaldex.domain.SpeciesFields
import dev.tlong.animaldex.domain.SpeciesSource
import dev.tlong.animaldex.domain.SpeciesSummary
import dev.tlong.animaldex.domain.TaxClass
import dev.tlong.animaldex.domain.USER_DEX_NUMBER_BASE
import dev.tlong.animaldex.domain.UserSpeciesRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** v1 ships exactly one region (DESIGN.md §2); it is a parameter so C03 stays a content problem. */
const val DEFAULT_REGION_ID = "pacific"

/**
 * The surface every screen consumes (ARCHITECTURE.md 3.1, 6.3). Slice 3 exposed reads only;
 * slice 5 adds the capture writes as the [CaptureStore] implementation — the user-added write
 * paths are still slice 7's.
 *
 * Search and filtering are deliberately *not* here: section 6.2 puts them in the ViewModel,
 * composed over these cold flows in memory.
 */
class DexRepository(
    private val db: AppDatabase,
    private val regionId: String = DEFAULT_REGION_ID,
) : CaptureStore, UserSpeciesStore, BackupStore {

    private val speciesFlow: Flow<List<SpeciesEntity>> = db.speciesDao().observeSpecies(regionId)
    private val membershipFlow: Flow<List<SpeciesEcosystemCrossRef>> =
        db.ecosystemDao().observeMemberships(regionId)
    private val entryStatusFlow: Flow<List<EntryStatusRow>> = db.entryDao().observeEntryStatuses()
    private val ecosystemFlow: Flow<List<EcosystemEntity>> =
        db.ecosystemDao().observeEcosystems(regionId)

    /** Every species in dex order — curated first, user-added trailing (M01/M02). */
    fun speciesSummaries(): Flow<List<SpeciesSummary>> =
        combine(speciesFlow, membershipFlow, entryStatusFlow) { species, memberships, statuses ->
            assembleSummaries(species, memberships, statuses)
        }

    fun ecosystems(): Flow<List<Ecosystem>> = ecosystemFlow.map { rows -> rows.map { it.toDomain() } }

    /** Shared by the grid header and the Stats screen (6.3). */
    fun dexProgress(): Flow<DexProgress> =
        combine(
            speciesFlow,
            membershipFlow,
            entryStatusFlow,
            ecosystemFlow,
        ) { species, memberships, statuses, ecosystems ->
            val caught = statuses.map { it.speciesId }.toSet()
            DexProgressMath.compute(
                regionId = regionId,
                species = species.map {
                    DexProgressMath.SpeciesRow(
                        id = it.id,
                        source = it.source,
                        taxClass = it.taxClass,
                        caught = it.id in caught,
                    )
                },
                memberships = memberships.map {
                    DexProgressMath.MembershipRow(it.speciesId, it.ecosystemId)
                },
                ecosystems = ecosystems.map { it.toDomain() },
            )
        }

    fun speciesDetail(speciesId: String): Flow<SpeciesDetail?> =
        combine(
            db.speciesDao().observeSpeciesById(speciesId),
            membershipFlow,
            entryStatusFlow,
        ) { species, memberships, statuses ->
            species?.let {
                val summaries = assembleSummaries(listOf(it), memberships, statuses)
                SpeciesDetail(
                    summary = summaries.first(),
                    habitatText = it.habitatText,
                    description = it.description,
                    imageUrl = it.imageUrl,
                    callUrl = it.callUrl,
                    infoUrl = it.infoUrl,
                    imageAttribution = it.imageAttribution,
                    callAttribution = it.callAttribution,
                    userEditedFields = it.userEditedFields,
                )
            }
        }

    fun captures(speciesId: String): Flow<List<Capture>> =
        db.captureDao().observeCaptures(speciesId).map { rows -> rows.map { it.toDomain() } }

    fun recentCaptures(limit: Int = 12): Flow<List<Capture>> =
        db.captureDao().observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    fun entry(speciesId: String): Flow<Entry?> =
        combine(db.entryDao().observeEntry(speciesId), entryStatusFlow) { entry, statuses ->
            entry?.let {
                Entry(
                    speciesId = it.speciesId,
                    caughtAt = it.caughtAt,
                    favoriteCaptureId = it.favoriteCaptureId,
                    captureCount = statuses.firstOrNull { s -> s.speciesId == it.speciesId }
                        ?.captureCount ?: 0,
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
            }
        }
    }

    override suspend fun setFavoriteCapture(speciesId: String, captureId: String?) {
        db.entryDao().setFavoriteCapture(speciesId, captureId)
    }

    override suspend fun updateCaptureReference(
        captureId: String,
        photoUri: String,
        thumbPath: String,
    ) {
        db.captureDao().updateReference(captureId, photoUri, thumbPath)
    }

    // -----------------------------------------------------------------------
    // User-added species (slice 7). Every decision is in `AddSpeciesRegistrar` and
    // `domain/UserSpecies.kt`; this half only reads and writes rows.
    // -----------------------------------------------------------------------

    override suspend fun maxUserDexNumber(regionId: String): Int? =
        db.speciesDao().maxUserDexNumber(regionId)

    override suspend fun userSpecies(speciesId: String): UserSpeciesRecord? {
        val row = db.speciesDao().speciesOnceById(speciesId) ?: return null
        if (row.source != SpeciesSource.USER) return null
        return row.toUserRecord()
    }

    override suspend fun upsertUserSpecies(record: UserSpeciesRecord, ecosystemIds: List<String>?) {
        db.withTransaction {
            db.speciesDao().upsert(record.toEntity())
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

    override suspend fun deleteUserSpecies(speciesId: String) {
        db.speciesDao().deleteByIds(listOf(speciesId))
    }

    // -----------------------------------------------------------------------
    // Export and import (slice 8, S01). Every rule lives in `data/backup/`; this
    // half reads rows and applies a plan.
    // -----------------------------------------------------------------------

    override suspend fun backupSnapshot(): BackupSnapshot {
        val species = db.speciesDao().speciesOnce(regionId)
        val ecosystemsBySpecies = db.ecosystemDao().membershipsOnce(regionId)
            .groupBy({ it.speciesId }, { it.ecosystemId })
        return BackupSnapshot(
            regionId = regionId,
            species = species.map { it.toBackup(ecosystemsBySpecies[it.id].orEmpty()) },
            entries = db.entryDao().entriesOnce().map {
                BackupEntry(it.speciesId, it.caughtAt, it.favoriteCaptureId)
            },
            captures = db.captureDao().capturesOnce().map { it.toDomain() },
        )
    }

    override suspend fun localSnapshot(): LocalSnapshot {
        val species = db.speciesDao().speciesOnce(regionId)
        return LocalSnapshot(
            speciesSources = species.associate { it.id to it.source },
            usedUserDexNumbers = species.filter { it.dexNumber > USER_DEX_NUMBER_BASE }
                .map { it.dexNumber }
                .toSet(),
            captureIds = db.captureDao().captureIdsOnce().toSet(),
            entries = db.entryDao().entriesOnce()
                .associate { it.speciesId to LocalEntry(it.caughtAt, it.favoriteCaptureId) },
            ecosystemIds = db.ecosystemDao().ecosystemsOnce(regionId).map { it.id }.toSet(),
        )
    }

    override suspend fun applyImport(plan: ImportPlan) {
        db.withTransaction {
            // Species first: both captures and entries carry a foreign key to it.
            plan.speciesToInsert.forEach { db.speciesDao().upsert(it.toEntity(regionId)) }
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
                    ),
                )
            }
        }
    }
}

internal fun SpeciesEntity.toBackup(ecosystemIds: List<String>) = BackupSpecies(
    id = id,
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
    callUrl = callUrl,
    infoUrl = infoUrl,
    imageAttribution = imageAttribution,
    callAttribution = callAttribution,
    userEditedFields = userEditedFields,
    ecosystemIds = ecosystemIds,
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
    taxClass = TaxClass.fromWireName(taxClass),
    habitatText = habitatText,
    description = description,
    imageUrl = imageUrl,
    callUrl = callUrl,
    infoUrl = infoUrl,
    imageAttribution = imageAttribution,
    callAttribution = callAttribution,
    silhouetteRes = silhouetteRes,
    userEditedFields = userEditedFields,
)

internal fun SpeciesEntity.toUserRecord() = UserSpeciesRecord(
    id = id,
    regionId = regionId,
    dexNumber = dexNumber,
    detailsPending = detailsPending,
    fields = SpeciesFields(
        commonName = commonName,
        scientificName = scientificName,
        taxClass = taxClass,
        habitatText = habitatText,
        description = description,
        imageUrl = imageUrl,
        imageAttribution = imageAttribution,
        callUrl = callUrl,
        callAttribution = callAttribution,
        infoUrl = infoUrl,
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
    callUrl = fields.callUrl,
    infoUrl = fields.infoUrl,
    imageAttribution = fields.imageAttribution,
    callAttribution = fields.callAttribution,
    silhouetteRes = fields.silhouetteRes,
    userEditedFields = userEditedFields,
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
            silhouetteRes = row.silhouetteRes,
            ecosystemIds = ecosystemsBySpecies[row.id].orEmpty(),
            caughtAt = status?.caughtAt,
            thumbPath = status?.thumbPath,
            captureCount = status?.captureCount ?: 0,
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
