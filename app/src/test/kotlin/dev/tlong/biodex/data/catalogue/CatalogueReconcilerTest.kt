package dev.tlong.biodex.data.catalogue

import dev.tlong.biodex.data.catalogue.CatalogueReconciler.ExistingSpecies
import dev.tlong.biodex.data.catalogue.CatalogueReconciler.ImportDecision
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
    ) = CatalogueSpecies(
        id = id,
        dexNumber = dexNumber,
        commonName = id,
        scientificName = "Genus $id",
        taxClass = taxClass,
        ecosystemIds = ecosystemIds,
        silhouetteRes = silhouetteRes,
    )

    private fun document(
        version: Int = 1,
        species: List<CatalogueSpecies>,
        ecosystemIds: List<String> = listOf("coastal-rainforest", "urban-suburban"),
    ) = CatalogueDocument(
        catalogueVersion = version,
        regionId = "pacific",
        regionName = "Pacific",
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
}
