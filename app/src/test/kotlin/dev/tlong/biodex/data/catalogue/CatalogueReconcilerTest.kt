package dev.tlong.biodex.data.catalogue

import dev.tlong.biodex.data.catalogue.CatalogueReconciler.ExistingSpecies
import dev.tlong.biodex.data.catalogue.CatalogueReconciler.ImportDecision
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ARCHITECTURE.md 3.3's invariants, tested against the pure decision function. Nothing
 * here needs Room, which is the point: these rules are what protect the user's life list,
 * and they must be provable without a phone.
 */
class CatalogueReconcilerTest {

    private fun assetSpecies(
        id: String,
        dexNumber: Int = 1,
        ecosystemIds: List<String> = listOf("coastal-rainforest"),
        taxClass: String = "bird",
        silhouetteRes: String? = "sil_bird",
        kingdom: String = "animal",
        uses: List<String> = emptyList(),
        usesNote: String? = null,
        medicinalActivities: List<String> = emptyList(),
        medicinalRecordCount: Int = 0,
        usesAttribution: String? = null,
    ) = CatalogueSpecies(
        id = id,
        dexNumber = dexNumber,
        commonName = id,
        scientificName = "Genus $id",
        taxClass = taxClass,
        kingdom = kingdom,
        ecosystemIds = ecosystemIds,
        silhouetteRes = silhouetteRes,
        uses = uses,
        usesNote = usesNote,
        medicinalActivities = medicinalActivities,
        medicinalRecordCount = medicinalRecordCount,
        usesAttribution = usesAttribution,
    )

    private fun document(
        version: Int = 1,
        species: List<CatalogueSpecies>,
        ecosystemIds: List<String> = listOf("coastal-rainforest", "urban-suburban"),
    ) = CatalogueDocument(
        catalogueVersion = version,
        regionId = "pacific",
        regionName = "Pacific USA",
        ecosystems = ecosystemIds.mapIndexed { i, id -> CatalogueEcosystem(id, id, i + 1) },
        species = species,
    )

    @Test
    fun `matching catalogue version decides on no work at all`() {
        val decision = CatalogueReconciler.decide(
            currentVersion = 4,
            document = document(version = 4, species = listOf(assetSpecies("a"))),
            existing = emptyList(),
        )
        assertEquals(ImportDecision.UpToDate, decision)
    }

    @Test
    fun `a never-imported database always applies`() {
        val decision = CatalogueReconciler.decide(
            currentVersion = null,
            document = document(version = 1, species = listOf(assetSpecies("a"))),
            existing = emptyList(),
        )
        assertTrue(decision is ImportDecision.Apply)
    }

    @Test
    fun `a differing catalogue version applies`() {
        val decision = CatalogueReconciler.decide(
            currentVersion = 1,
            document = document(version = 2, species = listOf(assetSpecies("a"))),
            existing = emptyList(),
        )
        assertTrue(decision is ImportDecision.Apply)
    }

    @Test
    fun `user-added species are never upserted, deleted or re-linked`() {
        val plan = CatalogueReconciler.plan(
            document = document(species = listOf(assetSpecies("heron"))),
            existing = listOf(
                ExistingSpecies("user-abc", SpeciesSource.USER, hasEntry = true),
                ExistingSpecies("user-def", SpeciesSource.USER, hasEntry = false),
            ),
        )

        assertTrue(plan.speciesUpserts.none { it.id.startsWith("user-") })
        assertEquals(emptyList<String>(), plan.speciesDeletions)
        assertTrue(plan.membershipReplacements.keys.none { it.startsWith("user-") })
    }

    @Test
    fun `an asset id colliding with an existing user row leaves that row alone`() {
        // Curated ids are slugs and user ids are user-<UUID>, so this cannot happen by
        // accident — the rule is here so that if it ever does, the user's row wins.
        val plan = CatalogueReconciler.plan(
            document = document(species = listOf(assetSpecies("varied-thrush"))),
            existing = listOf(ExistingSpecies("varied-thrush", SpeciesSource.USER, hasEntry = true)),
        )

        assertEquals(emptyList<String>(), plan.speciesUpserts.map { it.id })
        assertEquals(emptyList<String>(), plan.speciesDeletions)
        assertNull(plan.membershipReplacements["varied-thrush"])
    }

    @Test
    fun `a caught curated species dropped from a new asset is kept, not deleted`() {
        val plan = CatalogueReconciler.plan(
            document = document(version = 2, species = listOf(assetSpecies("heron"))),
            existing = listOf(
                ExistingSpecies("heron", SpeciesSource.CURATED, hasEntry = false),
                ExistingSpecies("retired-owl", SpeciesSource.CURATED, hasEntry = true),
            ),
        )

        assertEquals(emptyList<String>(), plan.speciesDeletions)
    }

    @Test
    fun `an uncaught curated species dropped from a new asset is deleted`() {
        val plan = CatalogueReconciler.plan(
            document = document(version = 2, species = listOf(assetSpecies("heron"))),
            existing = listOf(
                ExistingSpecies("heron", SpeciesSource.CURATED, hasEntry = false),
                ExistingSpecies("retired-owl", SpeciesSource.CURATED, hasEntry = false),
            ),
        )

        assertEquals(listOf("retired-owl"), plan.speciesDeletions)
    }

    @Test
    fun `ecosystem links are replaced only for species the asset carries`() {
        val plan = CatalogueReconciler.plan(
            document = document(version = 2, species = listOf(assetSpecies("heron"))),
            existing = listOf(
                ExistingSpecies("heron", SpeciesSource.CURATED, hasEntry = false),
                // Kept because it is caught: its existing links must not be rewritten.
                ExistingSpecies("retired-owl", SpeciesSource.CURATED, hasEntry = true),
                ExistingSpecies("user-abc", SpeciesSource.USER, hasEntry = true),
            ),
        )

        assertEquals(setOf("heron"), plan.membershipReplacements.keys)
    }

    @Test
    fun `an ecosystem id the asset never declares is dropped rather than failing the import`() {
        val plan = CatalogueReconciler.plan(
            document = document(
                species = listOf(
                    assetSpecies("heron", ecosystemIds = listOf("coastal-rainforest", "atlantis")),
                ),
            ),
            existing = emptyList(),
        )

        assertEquals(listOf("coastal-rainforest"), plan.membershipReplacements["heron"])
    }

    @Test
    fun `duplicate ecosystem tags collapse to one link`() {
        val plan = CatalogueReconciler.plan(
            document = document(
                species = listOf(
                    assetSpecies(
                        "heron",
                        ecosystemIds = listOf("coastal-rainforest", "coastal-rainforest"),
                    ),
                ),
            ),
            existing = emptyList(),
        )

        assertEquals(listOf("coastal-rainforest"), plan.membershipReplacements["heron"])
    }

    @Test
    fun `imported rows are curated, not pending, and carry no user-edited fields`() {
        val plan = CatalogueReconciler.plan(
            document = document(species = listOf(assetSpecies("heron"))),
            existing = emptyList(),
        )

        val row = plan.speciesUpserts.single()
        assertEquals(SpeciesSource.CURATED, row.source)
        assertEquals(false, row.detailsPending)
        assertEquals(emptyList<String>(), row.userEditedFields)
        assertEquals("pacific", row.regionId)
    }

    @Test
    fun `a missing silhouette falls back to the species class`() {
        val plan = CatalogueReconciler.plan(
            document = document(
                species = listOf(
                    assetSpecies("slug", taxClass = "other_invertebrate", silhouetteRes = null),
                ),
            ),
            existing = emptyList(),
        )

        val row = plan.speciesUpserts.single()
        assertEquals(TaxClass.OTHER_INVERTEBRATE, row.taxClass)
        assertEquals("sil_other_invertebrate", row.silhouetteRes)
    }

    @Test
    fun `an unrecognised class does not fail the import`() {
        val plan = CatalogueReconciler.plan(
            document = document(species = listOf(assetSpecies("thing", taxClass = "tardigrade"))),
            existing = emptyList(),
        )

        assertEquals(TaxClass.OTHER_INVERTEBRATE, plan.speciesUpserts.single().taxClass)
    }

    // -----------------------------------------------------------------------
    // The BioDex delta (11.1): two kingdoms in one asset, numbered per kingdom.
    // -----------------------------------------------------------------------

    @Test
    fun `the importer applies each kingdom's dex-number base`() {
        val plan = CatalogueReconciler.plan(
            document(
                species = listOf(
                    assetSpecies("heron", dexNumber = 3),
                    assetSpecies("elder", dexNumber = 47, taxClass = "shrub", kingdom = "plant"),
                ),
            ),
            existing = emptyList(),
        )

        val byId = plan.speciesUpserts.associateBy { it.id }
        // The asset numbers each kingdom from 1 so the curator never types 2047.
        assertEquals(3, byId.getValue("heron").dexNumber)
        assertEquals(2047, byId.getValue("elder").dexNumber)
    }

    @Test
    fun `the kingdom and the class agree on every planned row`() {
        val plan = CatalogueReconciler.plan(
            document(
                species = listOf(
                    assetSpecies("elder", taxClass = "shrub", kingdom = "plant"),
                    // A curator typo: the pipeline would have caught this, so reaching it
                    // means the asset shipped wrong.
                    assetSpecies("mixed", taxClass = "tree", kingdom = "animal"),
                    assetSpecies("mixed-2", taxClass = "bird", kingdom = "plant"),
                ),
            ),
            existing = emptyList(),
        )

        assertTrue(plan.speciesUpserts.all { it.kingdom == it.taxClass.kingdom })
        val byId = plan.speciesUpserts.associateBy { it.id }
        // The declared kingdom wins and the class falls back to that kingdom's default: one
        // species loses its growth form, rather than the whole import failing.
        assertEquals(Kingdom.ANIMAL, byId.getValue("mixed").kingdom)
        assertEquals(TaxClass.OTHER_INVERTEBRATE, byId.getValue("mixed").taxClass)
        assertEquals(Kingdom.PLANT, byId.getValue("mixed-2").kingdom)
        assertEquals(TaxClass.HERB, byId.getValue("mixed-2").taxClass)
    }

    @Test
    fun `uses, the note and the Duke's columns come through, and inconsistent ones do not`() {
        val plan = CatalogueReconciler.plan(
            document(
                species = listOf(
                    assetSpecies(
                        "elder",
                        taxClass = "shrub",
                        kingdom = "plant",
                        uses = listOf("edible", "medicinal", "delicious"),
                        usesNote = "Berries, late summer. Caution: raw berries are toxic.",
                        medicinalActivities = listOf("astringent", "diuretic"),
                        medicinalRecordCount = 60,
                        usesAttribution = "Duke's · CC0",
                    ),
                    // A note with no use behind it, and a credit line with no data behind it.
                    assetSpecies(
                        "fir",
                        taxClass = "tree",
                        kingdom = "plant",
                        usesNote = "Orphaned note.",
                        usesAttribution = "Duke's · CC0",
                    ),
                ),
            ),
            existing = emptyList(),
        )

        val byId = plan.speciesUpserts.associateBy { it.id }
        val elder = byId.getValue("elder")
        // "delicious" is not a use this app knows; a chip that can never match is dropped.
        assertEquals(listOf("edible", "medicinal"), elder.uses)
        assertEquals(60, elder.medicinalRecordCount)
        assertEquals(listOf("astringent", "diuretic"), elder.medicinalActivities)
        assertEquals("Duke's · CC0", elder.usesAttribution)

        val fir = byId.getValue("fir")
        assertTrue(fir.uses.isEmpty())
        assertNull(fir.usesNote)
        assertNull(fir.usesAttribution)
    }

    @Test
    fun `the v1 asset's animals import unchanged, with no kingdom field in the file`() {
        val plan = CatalogueReconciler.plan(
            document(species = listOf(assetSpecies("heron", dexNumber = 3))),
            existing = emptyList(),
        )

        val heron = plan.speciesUpserts.single()
        assertEquals(3, heron.dexNumber)
        assertEquals(Kingdom.ANIMAL, heron.kingdom)
        assertTrue(heron.uses.isEmpty())
        assertNull(heron.usesNote)
        assertEquals(0, heron.medicinalRecordCount)
    }

    @Test
    fun `the plan seeds the region row the header reads its name from`() {
        val plan = CatalogueReconciler.plan(
            document(species = listOf(assetSpecies("heron"))),
            existing = emptyList(),
        )

        assertEquals("pacific", plan.region.id)
        assertEquals("Pacific USA", plan.region.name)
    }
}
