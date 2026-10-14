package dev.tlong.biodex.data.repo

import dev.tlong.biodex.data.db.EntryStatusRow
import dev.tlong.biodex.data.db.SpeciesEcosystemCrossRef
import dev.tlong.biodex.data.db.SpeciesEntity
import dev.tlong.biodex.data.db.SpeciesRegionEntity
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid-cell join, which the repository does in memory over three Room flows rather
 * than in one wide SQL query. Slice 4 renders exactly what this produces, so the
 * caught/uncaught rule and the thumbnail choice are worth pinning here.
 */
class SummaryAssemblyTest {

    private fun species(
        id: String,
        dexNumber: Int,
        source: SpeciesSource = SpeciesSource.CURATED,
        regionId: String = "pacific",
    ) = SpeciesEntity(
        id = id,
        regionId = regionId,
        dexNumber = dexNumber,
        source = source,
        commonName = id,
        taxClass = TaxClass.BIRD,
        silhouetteRes = "sil_bird",
    )

    @Test
    fun `a species with no entry is uncaught, thumbnail-less and capture-free`() {
        val summaries = assembleSummaries(
            species = listOf(species("owl", 21)),
            memberships = emptyList(),
            statuses = emptyList(),
        )

        val owl = summaries.single()
        assertFalse(owl.caught)
        assertNull(owl.caughtAt)
        assertNull(owl.thumbPath)
        assertEquals(0, owl.captureCount)
    }

    @Test
    fun `a species with an entry carries its catch time, thumbnail and capture count`() {
        val summaries = assembleSummaries(
            species = listOf(species("heron", 3)),
            memberships = emptyList(),
            statuses = listOf(EntryStatusRow("heron", 1_700_000_000_000L, 4, "thumbnails/a.jpg")),
        )

        val heron = summaries.single()
        assertTrue(heron.caught)
        assertEquals(1_700_000_000_000L, heron.caughtAt)
        assertEquals("thumbnails/a.jpg", heron.thumbPath)
        assertEquals(4, heron.captureCount)
    }

    @Test
    fun `ecosystem membership rows land on the right species`() {
        val summaries = assembleSummaries(
            species = listOf(species("heron", 3), species("owl", 21)),
            memberships = listOf(
                SpeciesEcosystemCrossRef("heron", "riparian-wetland"),
                SpeciesEcosystemCrossRef("heron", "rocky-shore-kelp"),
                SpeciesEcosystemCrossRef("owl", "oak-chaparral"),
            ),
            statuses = emptyList(),
        )

        val byId = summaries.associateBy { it.id }
        assertEquals(
            listOf("riparian-wetland", "rocky-shore-kelp"),
            byId.getValue("heron").ecosystemIds,
        )
        assertEquals(listOf("oak-chaparral"), byId.getValue("owl").ecosystemIds)
    }

    @Test
    fun `dex order is preserved and user-added species render U-numbers`() {
        // M02: the DAO orders by dexNumber, and user rows start at 9001, so they trail.
        val summaries = assembleSummaries(
            species = listOf(
                species("heron", 3),
                species("owl", 21),
                species("user-1", 9001, SpeciesSource.USER),
            ),
            memberships = emptyList(),
            statuses = emptyList(),
        )

        assertEquals(listOf("#003", "#021", "U01"), summaries.map { it.displayNumber })
    }

    @Test
    fun `a regional dex lists its own species under its own numbers, and All numbers them once (D95)`() {
        val species = listOf(
            species("egret", 5),
            species("hornero", 1, regionId = "argentina"),
            species("mine", 9001, SpeciesSource.USER),
            species("puffball", 4001, regionId = "argentina").copy(kingdom = Kingdom.FUNGUS, taxClass = TaxClass.MUSHROOM),
        )
        val listings = listOf(
            SpeciesRegionEntity("egret", "pacific", 5),
            SpeciesRegionEntity("egret", "argentina", 2),
            SpeciesRegionEntity("hornero", "argentina", 1),
            SpeciesRegionEntity("mine", "pacific", 9001),
            SpeciesRegionEntity("puffball", "argentina", 4001),
        )
        fun view(regionId: String) =
            dexView(regionId, species, listings, listOf("pacific", "argentina")).map { it.id to it.dexNumber }

        assertEquals(listOf("egret" to 5, "mine" to 9001), view("pacific"))
        assertEquals(listOf("hornero" to 1, "egret" to 2, "puffball" to 4001), view("argentina"))
        assertEquals(listOf("egret" to 1, "hornero" to 2, "puffball" to 4001, "mine" to 9001), view(ALL_REGIONS_ID))
    }
}
