package dev.tlong.biodex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D36's hop measure, pinned without a device or Room. */
class TaxonDistanceTest {

    private fun species(
        id: String,
        dex: Int = 1,
        taxClass: TaxClass = TaxClass.MAMMAL,
        caughtAt: Long? = null,
        lineage: Lineage = Lineage.Unknown,
    ) = SpeciesSummary(
        id = id,
        regionId = "pacific",
        dexNumber = dex,
        source = SpeciesSource.CURATED,
        detailsPending = false,
        commonName = id,
        scientificName = id,
        taxClass = taxClass,
        kingdom = taxClass.kingdom,
        silhouetteRes = "sil_mammal",
        ecosystemIds = emptyList(),
        caughtAt = caughtAt,
        thumbPath = null,
        captureCount = 0,
        lineage = lineage,
    )

    private val squirrel = Lineage("Animalia", "Chordata", "Mammalia", "Rodentia", "Sciuridae")
    private val marmot = Lineage("Animalia", "Chordata", "Mammalia", "Rodentia", "Sciuridae")
    private val beaver = Lineage("Animalia", "Chordata", "Mammalia", "Rodentia", "Castoridae")
    private val jackrabbit = Lineage("Animalia", "Chordata", "Mammalia", "Lagomorpha", "Leporidae")
    private val slug = Lineage("Animalia", "Mollusca", "Gastropoda", "Stylommatophora", "Ariolimacidae")
    private val seaStar = Lineage("Animalia", "Echinodermata", "Asteroidea", "Forcipulatida", "Asteriidae")
    private val goose = Lineage("Animalia", "Chordata", "Aves", "Anseriformes", "Anatidae")
    private val chanterelle =
        Lineage("Fungi", "Basidiomycota", "Agaricomycetes", "Cantharellales", "Cantharellaceae")

    // GBIF's backbone gives no class at all to a ray-finned fish.
    private val chinook = Lineage("Animalia", "Chordata", null, "Salmoniformes", "Salmonidae")
    private val coho = Lineage("Animalia", "Chordata", null, "Salmoniformes", "Salmonidae")

    @Test
    fun `the hop scale`() {
        val cases = listOf(
            Triple("same family", squirrel to marmot, 2),
            Triple("same order", squirrel to beaver, 4),
            // The owner's own worked example.
            Triple("same class — squirrel to jackrabbit", squirrel to jackrabbit, 6),
            // Chordata both, but a bird and a mammal.
            Triple("same phylum", goose to squirrel, 8),
            // A sea star is an echinoderm and a goose a chordate: kingdom is all they share.
            Triple("same kingdom", seaStar to goose, 10),
            Triple("same kingdom, mollusc to bird", goose to slug, 10),
            Triple("different kingdoms", goose to chanterelle, 12),
            // Two species with the same path are two family-mates, which is 2. There is no
            // zero: a lineage stops at family, so it never identifies the species itself,
            // and `nearest` excludes the focal species by id rather than by distance.
            Triple("identical paths", squirrel to squirrel.copy(), 2),
            // A rank both lack is skipped: treating the gap as a mismatch put two salmon
            // 8 apart while they sit in one family.
            Triple("both lack a class", chinook to coho, 2),
            // A rank only one lacks stops the walk rather than inventing a shared ancestor.
            Triple("one lacks a class", chinook to goose, 8),
        )

        cases.forEach { (name, pair, expected) ->
            val (a, b) = pair
            assertEquals(name, expected, TaxonDistance.hops(a, b))
            assertEquals("$name, reversed", expected, TaxonDistance.hops(b, a))
        }
    }

    @Test
    fun `an unclassified species has no distance`() {
        assertNull(TaxonDistance.hops(Lineage.Unknown, squirrel))
        assertNull(TaxonDistance.hops(squirrel, Lineage.Unknown))
    }

    @Test
    fun `nearest orders by distance, naming the shared rank, and drops the unclassified`() {
        val focal = species("focal", dex = 1, lineage = squirrel)
        val all = listOf(
            focal,
            species("far", dex = 2, lineage = jackrabbit),
            species("mid", dex = 3, lineage = beaver),
            species("near", dex = 4, lineage = marmot),
            species("unclassified", dex = 5, lineage = Lineage.Unknown),
        )

        val result = TaxonDistance.nearest(focal, all, 5)

        assertEquals(listOf("near", "mid", "far"), result.map { it.species.id })
        assertEquals(listOf(2, 4, 6), result.map { it.hops })
        assertEquals(listOf("family", "order", "class"), result.map { it.sharedRank })
        assertEquals(listOf("Sciuridae", "Rodentia", "Mammalia"), result.map { it.sharedTaxon })
    }

    @Test
    fun `equal distances put the focal species' own kind first, then dex order`() {
        val focal =
            species("slug", taxClass = TaxClass.OTHER_INVERTEBRATE, dex = 114, lineage = slug)
        val all = listOf(
            focal,
            species("goose", taxClass = TaxClass.BIRD, dex = 1, lineage = goose),
            species("star", taxClass = TaxClass.OTHER_INVERTEBRATE, dex = 200, lineage = seaStar),
            species("urchin", taxClass = TaxClass.OTHER_INVERTEBRATE, dex = 150, lineage = seaStar),
        )

        val result = TaxonDistance.nearest(focal, all, 3)

        assertEquals(listOf(10, 10, 10), result.map { it.hops })
        assertEquals(listOf("urchin", "star", "goose"), result.map { it.species.id })
    }

    @Test
    fun `an unclassified focal species has no neighbours`() {
        val focal = species("focal", lineage = Lineage.Unknown)

        assertEquals(
            emptyList<Neighbour>(),
            TaxonDistance.nearest(focal, listOf(focal, species("other", lineage = squirrel)), 3),
        )
    }

    @Test
    fun `countAt counts every species at a distance, not only the shown ones`() {
        val focal = species("focal", dex = 1, lineage = squirrel)
        val all = listOf(focal) + (1..7).map { species("s$it", dex = it + 1, lineage = jackrabbit) }

        assertEquals(7, TaxonDistance.countAt(focal, all, 6))
        assertEquals(0, TaxonDistance.countAt(focal, all, 2))
    }
}
