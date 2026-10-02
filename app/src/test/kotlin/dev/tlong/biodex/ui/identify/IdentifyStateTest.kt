package dev.tlong.biodex.ui.identify

import dev.tlong.biodex.data.net.CandidateDetails
import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.net.MatchKind
import dev.tlong.biodex.data.net.SpeciesCandidate
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.LookupFields
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
        online: OnlineLookup = OnlineLookup.Idle,
    ) = runBlocking {
        identifyUiState(
            photo = photo,
            species = MutableStateFlow(catalogue),
            query = MutableStateFlow(query),
            selectedId = MutableStateFlow(selectedId),
            capturing = MutableStateFlow(capturing),
            online = MutableStateFlow(online),
        ).first()
    }

    private fun found(name: String, scientific: String) = OnlineLookup.Done(
        name,
        LookupOutcome.Resolved(
            candidates = listOf(SpeciesCandidate(scientific, taxClass = TaxClass.BIRD, matchKind = MatchKind.WEB_SEARCH)),
            selectedIndex = 0,
            details = CandidateDetails(LookupFields(scientificName = scientific)),
        ),
    )

    @Test
    fun `a name the dex holds is not looked up online, anything else is (D88)`() {
        listOf(
            "Varied Thrush" to "Varied Thrush",
            " western " to "western",
            "western tanager" to null,
            "a male western tanager" to null,
            "  " to null,
        ).forEach { (query, expected) -> assertEquals("query=$query", expected, onlineLookupName(catalogue, query, null)) }
        assertNull("a picked species needs no lookup", onlineLookupName(catalogue, "Varied Thrush", "western-tanager"))
    }

    @Test
    fun `a lookup that resolves to a held scientific name registers that species (D88)`() {
        val s = state(query = "louisiana tanager", online = found("louisiana tanager", "Piranga ludoviciana Wilson, 1811"))
        assertEquals("western-tanager", s.target?.id)
        assertFalse(s.registersNewSpecies)
        assertNull("a lookup for an older name says nothing", state(query = "tanager x", online = found("louisiana tanager", "Piranga ludoviciana")).onlineMatch)
    }

    @Test
    fun `search online is offered for an unheld name until it runs or answers (D90)`() {
        assertTrue(state(query = "Varied Thrush").canSearchOnline)
        assertFalse("the dex holds it", state(query = "western tanager").canSearchOnline)
        assertFalse(state(query = "Varied Thrush", online = OnlineLookup.Searching("Varied Thrush")).canSearchOnline)
        assertFalse(state(query = "Varied Thrush", online = found("Varied Thrush", "Ixoreus naevius")).canSearchOnline)
        val failed = OnlineLookup.Done("Varied Thrush", LookupOutcome.Failed("timeout"))
        assertTrue("a failure can be retried", state(query = "Varied Thrush", online = failed).canSearchOnline)
        assertTrue(state(query = "Varied Thrush", online = OnlineLookup.Offline("Varied Thrush")).canSearchOnline)
    }

    @Test
    fun `an answer for a name since edited away is not shown (D90)`() {
        val lookup = found("Varied Thrush", "Ixoreus naevius")
        assertEquals(OnlineLookup.Idle, state(query = "Varied Thrus", online = lookup).online)
        val picked = state(query = "Varied Thrush", selectedId = "western-tanager", online = lookup)
        assertEquals("a picked species hides the lookup", OnlineLookup.Idle, picked.online)
    }

    @Test
    fun `the add carries the lookup only when it was for the name being added (D88)`() {
        val lookup = found("Varied Thrush", "Ixoreus naevius")
        assertEquals(lookup.outcome, state(query = "Varied Thrush", online = lookup).prefetched)
        assertNull(state(query = "Varied Thrus", online = lookup).prefetched)
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
    fun `register needs a species or a name to add, and says which (D81)`() {
        assertFalse(state().canRegister)
        assertEquals("Register", state().registerLabel)
        val chosen = state(selectedId = "western-screech-owl")
        assertTrue(chosen.canRegister)
        assertEquals("Register — Western Screech-Owl", chosen.registerLabel)
        assertFalse("a capture in flight cannot start twice", state(selectedId = "western-screech-owl", capturing = true).canRegister)
    }

    @Test
    fun `a name typed in full registers that species with no tap on the list`() {
        val s = state(query = "western tanager")
        assertEquals("western-tanager", s.target?.id)
        assertFalse(s.registersNewSpecies)
        assertNull("a partial name picks nothing", state(query = "western").target)
        assertEquals("a longer name that holds it picks it (D88)", "western-tanager", state(query = "western tanager bird").target?.id)
    }

    @Test
    fun `a name outside the dex registers as a new species`() {
        val s = state(query = "Varied Thrush")
        assertTrue(s.canRegister)
        assertTrue(s.registersNewSpecies)
        assertEquals("Register — add “Varied Thrush”", s.registerLabel)
        assertFalse("a picked species wins over the add", state(query = "Varied Thrush", selectedId = "western-tanager").registersNewSpecies)
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
