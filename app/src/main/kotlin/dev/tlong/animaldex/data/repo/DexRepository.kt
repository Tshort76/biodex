package dev.tlong.animaldex.data.repo

import androidx.room.withTransaction
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
import dev.tlong.animaldex.domain.SpeciesSummary
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
) : CaptureStore {

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
}

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
