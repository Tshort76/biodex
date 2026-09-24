package dev.tlong.biodex.ui.identify

import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.ui.capture.PickedPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D78. The Identify screen: which species a search offers, and what the capture button does. */
class IdentifyStateTest {

    private fun species(id: String, number: Int, common: String, scientific: String?) =
        SpeciesSummary(
            id = id,
            regionId = "pacific",
            dexNumber = number,
            source = SpeciesSource.CURATED,
            detailsPending = false,
            commonName = common,
            scientificName = scientific,
            taxClass = TaxClass.BIRD,
            kingdom = Kingdom.ANIMAL,
            silhouetteRes = "sil_bird",
            ecosystemIds = emptyList(),
            caughtAt = null,
            thumbPath = null,
            captureCount = 0,
        )

    private val catalogue = listOf(
        species("tanager-hunter", 5, "Tanager Hunter", null),
        species("great-blue-heron", 3, "Great Blue Heron", "Ardea herodias"),
        species("western-screech-owl", 21, "Western Screech-Owl", "Megascops kennicottii"),
        species("western-tanager", 34, "Western Tanager", "Piranga ludoviciana"),
    )

    private val photo = PickedPhoto("content://media/1", "IMG_1.jpg")

    private fun state(
        query: String = "",
        selectedId: String? = null,
        capturing: Boolean = false,
    ) = runBlocking {
        identifyUiState(
            photo = photo,
            species = MutableStateFlow(catalogue),
            query = MutableStateFlow(query),
            selectedId = MutableStateFlow(selectedId),
            capturing = MutableStateFlow(capturing),
        ).first()
    }

    @Test
    fun `matches lead with the best match, then dex order (D74)`() {
        fun ids(query: String) = rankedMatches(catalogue, query).map { it.id }
        assertEquals("the exact name first", "western-tanager", ids("western tanager").first())
        assertEquals("equal matches keep dex order", listOf("western-screech-owl", "western-tanager"), ids("western"))
        assertEquals("the scientific name matches too", listOf("great-blue-heron"), ids("ardea"))
        assertEquals("nothing typed lists nothing", emptyList<String>(), ids("  "))
    }

    @Test
    fun `capturing needs a selection, and names it`() {
        assertFalse(state().canCapture)
        assertEquals("Capture", state().captureLabel)
        val chosen = state(selectedId = "western-screech-owl")
        assertTrue(chosen.canCapture)
        assertEquals("Capture — Western Screech-Owl", chosen.captureLabel)
        assertFalse("a capture in flight cannot start twice", state(selectedId = "western-screech-owl", capturing = true).canCapture)
    }

    @Test
    fun `a selection survives a search that filters it out of view`() {
        val s = state(query = "heron", selectedId = "western-screech-owl")
        assertEquals(listOf("great-blue-heron"), s.results.map { it.id })
        assertEquals("western-screech-owl", s.selected?.id)
    }

    @Test
    fun `a name outside the dex is offered as an add (D69)`() {
        assertEquals("Varied Thrush", state(query = "Varied Thrush").addableName)
        assertNull(state(query = "western").addableName)
    }

    @Test
    fun `the name is taken from what was copied only when it looks like one`() {
        val cases = mapOf(
            "Varied Thrush" to "Varied Thrush",
            "  Ixoreus naevius.  " to "Ixoreus naevius",
            "Grass-veneer moth\nSpecies of moth" to "Grass-veneer moth",
            "\n  Downy Woodpecker\n" to "Downy Woodpecker",
            null to null,
            "" to null,
            "12345" to null,
            "https://lens.google.com/x" to null,
            "x".repeat(61) to null,
        )
        cases.forEach { (clip, name) -> assertEquals("clip=$clip", name, nameFromClipboard(clip)) }
    }
}
