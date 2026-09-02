package dev.tlong.biodex.data.catalogue

import dev.tlong.biodex.data.catalogue.CatalogueReconciler.ExistingSpecies
import dev.tlong.biodex.data.catalogue.CatalogueReconciler.ImportPlan
import dev.tlong.biodex.data.db.CaptureEntity
import dev.tlong.biodex.data.db.EcosystemEntity
import dev.tlong.biodex.data.db.EntryEntity
import dev.tlong.biodex.data.db.MetaEntity
import dev.tlong.biodex.data.db.RegionEntity
import dev.tlong.biodex.data.db.SpeciesEntity
import dev.tlong.biodex.domain.SpeciesSource

/**
 * An in-memory stand-in for the database, holding the tables the importer must never
 * damage (`entries`, `captures`) as well as the ones it writes.
 *
 * It deliberately reproduces one piece of SQLite behaviour: deleting a species cascades to
 * its memberships, its entry and its captures. That cascade is the reason ARCHITECTURE.md
 * 3.3 forbids deleting a caught species, so a fake that ignored it would make the most
 * important test in this slice vacuous.
 */
class FakeCatalogueStore : CatalogueStore {

    val species = linkedMapOf<String, SpeciesEntity>()
    val ecosystems = linkedMapOf<String, EcosystemEntity>()
    val regions = linkedMapOf<String, RegionEntity>()
    val memberships = mutableSetOf<Pair<String, String>>()
    val entries = linkedMapOf<String, EntryEntity>()
    val captures = mutableListOf<CaptureEntity>()
    val meta = mutableMapOf<String, String>()

    /** How many times a plan was actually applied — an up-to-date run must not bump it. */
    var applyCount = 0
        private set

    override suspend fun catalogueVersion(): Int? =
        meta[MetaEntity.KEY_CATALOGUE_VERSION]?.trim()?.toIntOrNull()

    override suspend fun existingSpecies(regionId: String): List<ExistingSpecies> =
        species.values
            .filter { it.regionId == regionId }
            .map { ExistingSpecies(it.id, it.source, entries.containsKey(it.id)) }

    override suspend fun apply(plan: ImportPlan) {
        applyCount++
        regions[plan.region.id] = plan.region
        plan.ecosystems.forEach { ecosystems[it.id] = it }
        plan.speciesUpserts.forEach { species[it.id] = it }
        plan.speciesDeletions.forEach { cascadeDeleteSpecies(it) }
        plan.membershipReplacements.forEach { (speciesId, ecosystemIds) ->
            memberships.removeAll { it.first == speciesId }
            ecosystemIds.forEach { memberships += speciesId to it }
        }
        meta[MetaEntity.KEY_CATALOGUE_VERSION] = plan.catalogueVersion.toString()
        meta[MetaEntity.KEY_SCHEMA_SEEDED_AT] = "0"
    }

    private fun cascadeDeleteSpecies(speciesId: String) {
        species.remove(speciesId)
        memberships.removeAll { it.first == speciesId }
        entries.remove(speciesId)
        captures.removeAll { it.speciesId == speciesId }
    }

    // --- seeding helpers -----------------------------------------------------------

    fun seedSpecies(vararg rows: SpeciesEntity) = rows.forEach { species[it.id] = it }

    fun seedCaught(speciesId: String, captureId: String = "$speciesId-capture") {
        entries[speciesId] = EntryEntity(speciesId, caughtAt = 1_700_000_000_000L)
        captures += CaptureEntity(
            id = captureId,
            speciesId = speciesId,
            photoUri = "content://media/external/images/media/1",
            thumbPath = "thumbnails/$captureId.jpg",
            takenAt = 1_700_000_000_000L,
            createdAt = 1_700_000_000_000L,
        )
    }
}

fun curatedSpecies(
    id: String,
    dexNumber: Int = 1,
    commonName: String = id,
    regionId: String = "pacific",
) = SpeciesEntity(
    id = id,
    regionId = regionId,
    dexNumber = dexNumber,
    source = SpeciesSource.CURATED,
    commonName = commonName,
    taxClass = dev.tlong.biodex.domain.TaxClass.BIRD,
    silhouetteRes = "sil_bird",
)

fun userSpecies(
    id: String,
    dexNumber: Int = 9001,
    commonName: String = id,
    regionId: String = "pacific",
) = SpeciesEntity(
    id = id,
    regionId = regionId,
    dexNumber = dexNumber,
    source = SpeciesSource.USER,
    commonName = commonName,
    taxClass = dev.tlong.biodex.domain.TaxClass.BIRD,
    silhouetteRes = "sil_bird",
    userEditedFields = listOf("habitatText"),
)
