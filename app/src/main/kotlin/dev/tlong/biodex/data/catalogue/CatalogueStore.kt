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

    /** `meta.catalogueVersion`, or null when the catalogue has never been imported. */
    suspend fun catalogueVersion(): Int?

    suspend fun existingSpecies(regionId: String): List<ExistingSpecies>

    /** Applies the whole plan atomically. */
    suspend fun apply(plan: ImportPlan)
}

class RoomCatalogueStore(private val db: AppDatabase) : CatalogueStore {

    override suspend fun catalogueVersion(): Int? =
        db.metaDao().value(MetaEntity.KEY_CATALOGUE_VERSION)?.trim()?.toIntOrNull()

    override suspend fun existingSpecies(regionId: String): List<ExistingSpecies> {
        val caught = db.entryDao().speciesIdsWithEntries().toSet()
        return db.speciesDao().speciesOnce(regionId).map {
            ExistingSpecies(id = it.id, source = it.source, hasEntry = it.id in caught)
        }
    }

    /** Step 2 of ARCHITECTURE.md 3.3: everything below lands in one transaction. */
    override suspend fun apply(plan: ImportPlan) = db.withTransaction {
        db.ecosystemDao().upsertAll(plan.ecosystems)
        db.speciesDao().upsertAll(plan.speciesUpserts)

        if (plan.speciesDeletions.isNotEmpty()) {
            db.speciesDao().deleteByIds(plan.speciesDeletions)
        }

        val touched = plan.membershipReplacements.keys.toList()
        if (touched.isNotEmpty()) {
            db.ecosystemDao().deleteMembershipsFor(touched)
            val rows = plan.membershipReplacements.flatMap { (speciesId, ecosystemIds) ->
                ecosystemIds.map { SpeciesEcosystemCrossRef(speciesId, it) }
            }
            db.ecosystemDao().upsertMemberships(rows)
        }

        db.metaDao().put(
            MetaEntity(MetaEntity.KEY_CATALOGUE_VERSION, plan.catalogueVersion.toString()),
        )
        db.metaDao().put(
            MetaEntity(MetaEntity.KEY_SCHEMA_SEEDED_AT, System.currentTimeMillis().toString()),
        )
    }
}
