package dev.tlong.biodex.data.catalogue

import androidx.room.withTransaction
import dev.tlong.biodex.data.catalogue.CatalogueReconciler.ExistingSpecies
import dev.tlong.biodex.data.catalogue.CatalogueReconciler.ImportPlan
import dev.tlong.biodex.data.db.AppDatabase
import dev.tlong.biodex.data.db.MetaEntity
import dev.tlong.biodex.data.db.SpeciesEcosystemCrossRef

/**
 * The narrow slice of persistence the importer needs. It exists so the importer can be
 * exercised end-to-end on the JVM against an in-memory fake — Room itself needs a device,
 * but none of ARCHITECTURE.md 3.3's invariants are about Room.
 */
interface CatalogueStore {

    /** The region's imported catalogue version, or null when it has never been imported (D94). */
    suspend fun catalogueVersion(regionId: String): Int?

    /** Every species in every region (D95): which dexes list a row decides what an import may do. */
    suspend fun existingSpecies(): List<ExistingSpecies>

    /** Applies the whole plan atomically. */
    suspend fun apply(plan: ImportPlan)
}

class RoomCatalogueStore(private val db: AppDatabase) : CatalogueStore {

    override suspend fun catalogueVersion(regionId: String): Int? =
        db.metaDao().value(MetaEntity.catalogueVersionKey(regionId))?.trim()?.toIntOrNull()

    override suspend fun existingSpecies(): List<ExistingSpecies> {
        val caught = db.entryDao().speciesIdsWithEntries().toSet()
        val listings = db.speciesRegionDao().allOnce()
            .groupBy({ it.speciesId }, { it.regionId to it.dexNumber })
        return db.speciesDao().allSpeciesOnce().map {
            ExistingSpecies(
                id = it.id,
                source = it.source,
                hasEntry = it.id in caught,
                scientificName = it.scientificName,
                homeRegionId = it.regionId,
                regions = listings[it.id].orEmpty().toMap(),
            )
        }
    }

    /** Step 2 of ARCHITECTURE.md 3.3: everything below lands in one transaction. */
    override suspend fun apply(plan: ImportPlan) = db.withTransaction {
        // The region row goes in first: the grid header reads its name, and a species
        // landing before its region would flash an empty pill on a fresh install.
        db.regionDao().upsertAll(listOf(plan.region))
        db.ecosystemDao().upsertAll(plan.ecosystems)
        db.speciesDao().upsertAll(plan.speciesUpserts)

        // D89: the curated row exists now, so each merged user species hands it its entry and
        // its sightings before it goes. The user's choices on the entry win where the curated
        // row has none; the caught date is re-derived from the moved photos below.
        for ((from, into) in plan.merges) {
            val moving = db.entryDao().entryOnce(from)
            if (moving != null) {
                val held = db.entryDao().entryOnce(into)
                db.entryDao().upsert(
                    held?.copy(
                        favoriteCaptureId = held.favoriteCaptureId ?: moving.favoriteCaptureId,
                        preferOwnPhoto = held.preferOwnPhoto || moving.preferOwnPhoto,
                    ) ?: moving.copy(speciesId = into),
                )
            }
            db.captureDao().moveToSpecies(from, into)
        }
        if (plan.merges.isNotEmpty()) {
            db.speciesDao().deleteByIds(plan.merges.keys.toList())
            db.entryDao().syncAllCaughtAt()
        }

        if (plan.speciesDeletions.isNotEmpty()) {
            db.speciesDao().deleteByIds(plan.speciesDeletions)
        }

        db.speciesRegionDao().deleteRegion(plan.regionId)
        db.speciesRegionDao().upsertAll(plan.regionMembers)
        plan.carriedListings.forEach { db.speciesRegionDao().insertIfAbsent(it) }

        val touched = plan.membershipReplacements.keys.toList()
        if (touched.isNotEmpty()) {
            db.ecosystemDao().deleteRegionMembershipsFor(touched, plan.regionId)
            val rows = plan.membershipReplacements.flatMap { (speciesId, ecosystemIds) ->
                ecosystemIds.map { SpeciesEcosystemCrossRef(speciesId, it) }
            }
            db.ecosystemDao().upsertMemberships(rows)
        }

        db.metaDao().put(
            MetaEntity(MetaEntity.catalogueVersionKey(plan.regionId), plan.catalogueVersion.toString()),
        )
        db.metaDao().put(
            MetaEntity(MetaEntity.KEY_SCHEMA_SEEDED_AT, System.currentTimeMillis().toString()),
        )
    }
}
