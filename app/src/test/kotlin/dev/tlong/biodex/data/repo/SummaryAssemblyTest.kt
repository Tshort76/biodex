package dev.tlong.biodex.data.repo

import dev.tlong.biodex.data.db.EntryStatusRow
import dev.tlong.biodex.data.db.SpeciesEcosystemCrossRef
import dev.tlong.biodex.data.db.SpeciesEntity
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
    ) = SpeciesEntity(
        id = id,
        regionId = "pacific",
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
        // M02: the DAO orders by dexNumber, and user rows start at 1001, so they trail.
        val summaries = assembleSummaries(
            species = listOf(
                species("heron", 3),
                species("owl", 21),
                species("user-1", 1001, SpeciesSource.USER),
            ),
            memberships = emptyList(),
            statuses = emptyList(),
        )

        assertEquals(listOf("#003", "#021", "U01"), summaries.map { it.displayNumber })
    }
}
