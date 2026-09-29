package dev.tlong.biodex.ui.nearest

import dev.tlong.biodex.domain.Lineage
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D36's screen state, over a plain list rather than a repository. */
class NearestStateTest {

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

    private val sciuridae = Lineage("Animalia", "Chordata", "Mammalia", "Rodentia", "Sciuridae")
    private val leporidae = Lineage("Animalia", "Chordata", "Mammalia", "Lagomorpha", "Leporidae")

    private suspend fun state(all: List<SpeciesSummary>, id: String) =
        nearestUiState(flowOf(all), id).first()

    @Test
    fun `it names three, however many are tied`() = runBlocking {
        val focal = species("focal", dex = 1, lineage = sciuridae)
        val all = listOf(focal) + (1..9).map { species("r$it", dex = it + 1, lineage = leporidae) }

        val result = state(all, "focal")

        assertEquals(NEAREST_COUNT, result.neighbours.size)
        assertEquals(3, NEAREST_COUNT)
    }

    @Test
    fun `the footnote counts everyone at the furthest shown distance`() = runBlocking {
        val focal = species("focal", dex = 1, lineage = sciuridae)
        val all = listOf(focal) + (1..9).map { species("r$it", dex = it + 1, lineage = leporidae) }

        val result = state(all, "focal")

        assertEquals(6, result.furthestHops)
        assertEquals(9, result.tiedAtFurthest)
        // Nine at six hops, three of them shown.
        assertEquals(6, result.overflowAtFurthest)
    }

    @Test
    fun `no footnote when nothing was left out`() = runBlocking {
        val focal = species("focal", dex = 1, lineage = sciuridae)
        val all = listOf(focal, species("only", dex = 2, lineage = leporidae))

        assertEquals(0, state(all, "focal").overflowAtFurthest)
    }

    @Test
    fun `a missing id resolves to nothing rather than to some other species`() = runBlocking {
        val result = state(listOf(species("real", lineage = sciuridae)), "gone")

        assertTrue(result.missing)
        assertEquals(emptyList<Any>(), result.neighbours)
    }

    @Test
    fun `an unclassified species says so instead of showing neighbours`() = runBlocking {
        val all = listOf(
            species("pending", lineage = Lineage.Unknown),
            species("other", dex = 2, lineage = sciuridae),
        )

        val result = state(all, "pending")

        assertTrue(result.unclassified)
        assertFalse(result.missing)
        assertEquals(emptyList<Any>(), result.neighbours)
    }
}
