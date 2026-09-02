package dev.tlong.biodex.ui

import dev.tlong.biodex.data.catalogue.CatalogueDocument
import dev.tlong.biodex.data.catalogue.CatalogueSpecies
import dev.tlong.biodex.data.catalogue.catalogueJson
import dev.tlong.biodex.domain.DexProgress
import dev.tlong.biodex.domain.DexProgressMath
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.SpeciesDetail
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.storedDexNumber

/**
 * The two-kingdom fixture of ARCHITECTURE.md 11.6 — ten animals and six plants, of which one
 * carries both uses with a caution and a Duke's line, one is medicinal-only with activities
 * and no curated note, and two have no uses at all.
 *
 * It exists so slice 11 is checkable **before slice 10's asset exists**: the shipped
 * `pacific.json` is still 120 animals, so without this every plant assertion below would be
 * vacuous. Same pattern as slice 3's `fixture-pacific.json`.
 *
 * The asset-to-domain mapping lives in `DexRepository`, which this slice does not own, so the
 * projection below is a small local one. It applies `storedDexNumber` exactly as the importer
 * does — the fixture, like the real asset, numbers plants 1..n per kingdom.
 */
object TwoKingdomFixture {

    private val document: CatalogueDocument by lazy {
        val stream = checkNotNull(
            TwoKingdomFixture::class.java.classLoader
                ?.getResourceAsStream("catalogue/fixture-two-kingdom.json"),
        ) { "the two-kingdom fixture is missing from the test classpath" }
        catalogueJson.decodeFromString(stream.use { it.readBytes().decodeToString() })
    }

    val ecosystems: List<Ecosystem>
        get() = document.ecosystems.map {
            Ecosystem(id = it.id, regionId = document.regionId, name = it.name, sortOrder = it.sortOrder)
        }

    /** Every species as a grid summary. [caught] names the ids the user has registered. */
    fun summaries(caught: Set<String> = emptySet()): List<SpeciesSummary> =
        document.species.map { summary(it, caught) }

    fun summary(id: String, caught: Set<String> = emptySet()): SpeciesSummary =
        summary(document.species.single { it.id == id }, caught)

    /** The detail model for one species, carrying the uses block the fixture declares. */
    fun detail(id: String, caught: Set<String> = emptySet()): SpeciesDetail {
        val row = document.species.single { it.id == id }
        return SpeciesDetail(
            summary = summary(row, caught),
            habitatText = row.habitatText,
            description = row.description,
            imageUrl = row.imageUrl,
            callUrl = row.callUrl,
            infoUrl = row.infoUrl,
            imageAttribution = row.imageAttribution,
            callAttribution = row.callAttribution,
            usesNote = row.usesNote,
            medicinalActivities = row.medicinalActivities,
            medicinalRecordCount = row.medicinalRecordCount,
            usesAttribution = row.usesAttribution,
            userEditedFields = emptyList(),
        )
    }

    fun progress(species: List<SpeciesSummary>): DexProgress = DexProgressMath.compute(
        regionId = document.regionId,
        regionName = document.regionName,
        species = species.map {
            DexProgressMath.SpeciesRow(
                id = it.id,
                source = it.source,
                taxClass = it.taxClass,
                kingdom = it.kingdom,
                caught = it.caught,
            )
        },
        memberships = species.flatMap { row ->
            row.ecosystemIds.map { DexProgressMath.MembershipRow(row.id, it) }
        },
        ecosystems = ecosystems,
    )

    private fun summary(row: CatalogueSpecies, caught: Set<String>): SpeciesSummary {
        val kingdom = Kingdom.fromWireName(row.kingdom)
        return SpeciesSummary(
            id = row.id,
            regionId = document.regionId,
            dexNumber = storedDexNumber(kingdom, row.dexNumber),
            source = SpeciesSource.CURATED,
            detailsPending = false,
            commonName = row.commonName,
            scientificName = row.scientificName,
            taxClass = TaxClass.fromWireName(row.taxClass),
            kingdom = kingdom,
            uses = PlantUse.setFromWireNames(row.uses),
            silhouetteRes = row.silhouetteRes ?: "sil_other_invertebrate",
            ecosystemIds = row.ecosystemIds,
            caughtAt = if (row.id in caught) 1_756_512_000_000L else null,
            thumbPath = null,
            captureCount = if (row.id in caught) 1 else 0,
        )
    }
}
