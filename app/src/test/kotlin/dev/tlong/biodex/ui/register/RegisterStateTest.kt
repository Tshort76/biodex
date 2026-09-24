package dev.tlong.biodex.ui.register

import dev.tlong.biodex.domain.GazetteerPlace
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlaceAnswer
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M07's screen state. What matters here is when the Register button may be pressed: it is the
 * gate on the whole write path, and a screen that lets it be pressed with no photo or no
 * species would either crash or create a capture pointing at nothing.
 */
class RegisterStateTest {

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
        species("great-blue-heron", 3, "Great Blue Heron", "Ardea herodias"),
        species("western-screech-owl", 21, "Western Screech-Owl", "Megascops kennicottii"),
        species("western-tanager", 34, "Western Tanager", "Piranga ludoviciana"),
    )

    private fun state(
        query: String = "",
        selectedId: String? = null,
        photo: PickedPhoto? = null,
        registering: Boolean = false,
        error: String? = null,
        placePrompt: PlacePrompt? = null,
    ) = runBlocking {
        registerUiState(
            species = MutableStateFlow(catalogue),
            query = MutableStateFlow(query),
            selectedSpeciesId = MutableStateFlow(selectedId),
            photo = MutableStateFlow(photo),
            registering = MutableStateFlow(registering),
            error = MutableStateFlow(error),
            placePrompt = MutableStateFlow(placePrompt),
        ).first()
    }

    /**
     * D68. The prompt's own state: what is typed, what to offer, and what a registration would
     * write. The ranking itself is `PlaceSuggestTest`'s; what is pinned here is the label, since
     * that is the string that ends up on a sighting forever.
     */
    @Test
    fun `the place prompt offers the list and writes the list's spelling when it matches`() = runBlocking {
        val gazetteer = listOf(
            GazetteerPlace("Bear Valley", "CA", 0, lat = 38.4665, lng = -120.0441),
            GazetteerPlace("Bear Valley Trail", "CA", 1, lat = 38.0405, lng = -122.7996),
        )
        fun search(typed: String) = runBlocking {
            placeSearchState(
                query = MutableStateFlow(typed),
                gazetteer = MutableStateFlow(gazetteer),
                recent = MutableStateFlow(listOf("Tomales Bay, California")),
            ).first()
        }

        val empty = search("")
        assertEquals("nothing typed offers where we have been", listOf("Tomales Bay, California"), empty.suggestions)
        assertNull("and an empty prompt still writes nothing", empty.answer)
        assertNull(search("   ").answer)

        val typing = search("bear")
        assertEquals(
            listOf("Bear Valley, California", "Bear Valley Trail, California"),
            typing.suggestions,
        )

        val chosen = search("bear valley, california")
        assertTrue(chosen.isKnown)
        assertEquals(
            "the list's spelling is what gets written, with its point",
            PlaceAnswer("Bear Valley, California", lat = 38.4665, lng = -120.0441),
            chosen.answer,
        )

        val ownWords = search("  out behind the barn  ")
        assertFalse("a place the list has never heard of is still a place", ownWords.isKnown)
        assertEquals("written as typed, trimmed, with no point", PlaceAnswer("out behind the barn"), ownWords.answer)
    }

    @Test
    fun `the prompt writes the search's answer only when it was computed for this text`() {
        val bear = PlaceAnswer("Bear Valley, California", lat = 38.4665, lng = -120.0441)
        val caughtUp = PlaceSearchState(query = "bear valley, california", canonical = bear)
        assertEquals(bear, placeAnswerFor("bear valley, california", caughtUp))
        assertEquals(
            "a search a keystroke behind is not trusted; the text is taken as typed",
            PlaceAnswer("bear valley, californiax"),
            placeAnswerFor("bear valley, californiax", caughtUp),
        )
        assertNull(placeAnswerFor("  ", caughtUp))
    }

    /** A photo whose EXIF carries coordinates — the place is answered without typing (D60). */
    private val photo = PickedPhoto("content://media/1", "IMG_1.jpg", hasLocation = true)

    /** The common case (R3): the picker stripped the GPS, so the place must be typed. */
    private val stripped = PickedPhoto("content://media/2", "IMG_2.jpg", hasLocation = false)

    @Test
    fun `search matches common and scientific names, offline, in dex order`() {
        assertEquals(
            listOf("Western Screech-Owl", "Western Tanager"),
            state(query = "western").results.map { it.commonName },
        )
        assertEquals(
            listOf("Great Blue Heron"),
            state(query = "ardea").results.map { it.commonName },
        )
        assertEquals(listOf(3, 21, 34), state().results.map { it.dexNumber })
    }

    @Test
    fun `registering needs both a species and a photo`() {
        assertFalse(state().canRegister)
        assertFalse(state(selectedId = "western-screech-owl").canRegister)
        assertFalse(state(photo = photo).canRegister)
        assertTrue(state(selectedId = "western-screech-owl", photo = photo).canRegister)
    }

    @Test
    fun `a stripped photo still lights the button - the tap asks for the place (D64)`() {
        val owl = "western-screech-owl"
        val s = state(selectedId = owl, photo = stripped)
        assertTrue("the button is live", s.canRegister)
        assertTrue("and the tap will prompt", s.needsPlacePrompt)

        val located = state(selectedId = owl, photo = photo)
        assertTrue(located.canRegister)
        assertFalse("GPS on the photo means no prompt", located.needsPlacePrompt)

        val reading = state(selectedId = owl, photo = stripped.copy(hasLocation = null))
        assertFalse("the button waits while the EXIF is still being read", reading.canRegister)
        assertFalse("and never prompts for a photo that may yet answer", reading.needsPlacePrompt)
    }

    @Test
    fun `the prompt is state, not a field (D64)`() {
        assertNull(state(photo = stripped).placePrompt)
        assertEquals(PlacePrompt.REGISTER, state(placePrompt = PlacePrompt.REGISTER).placePrompt)
    }

    @Test
    fun `a registration already in flight cannot be started twice`() {
        assertFalse(
            state(selectedId = "western-screech-owl", photo = photo, registering = true)
                .canRegister,
        )
    }

    @Test
    fun `a selection survives a query that filters it out of the visible list`() {
        val s = state(query = "heron", selectedId = "western-screech-owl", photo = photo)
        assertEquals(listOf("Great Blue Heron"), s.results.map { it.commonName })
        assertEquals("Western Screech-Owl", s.selected?.commonName)
        assertTrue("the selection is still what would be registered", s.canRegister)
    }

    @Test
    fun `a name outside the dex is the add path, not an error (D69)`() {
        val s = state(query = "varied thrush")
        assertTrue(s.noResults)
        assertNull(s.error)
        assertFalse(s.canRegister)
    }

    @Test
    fun `an empty query is not a no-results state`() {
        assertFalse(state(query = "   ").noResults)
    }

    @Test
    fun `the button names the species it will register`() {
        assertEquals(
            "Register — Western Screech-Owl",
            state(selectedId = "western-screech-owl").registerLabel,
        )
        assertEquals("Register", state().registerLabel)
    }

    // -----------------------------------------------------------------------
    // D18. The screen pins the search field and docks the buttons, so the list no longer has
    // to be short to keep them reachable — and arriving with a species chosen has to say where
    // in the list that species is.
    // -----------------------------------------------------------------------

    /** Stands in for the two-kingdom catalogue: 200 species, in dex order. */
    private val fullCatalogue = (1..200).map { species("s-$it", it, "Species $it", null) }

    private fun fullState(query: String = "", preselectedSpeciesId: String? = null) = runBlocking {
        registerUiState(
            species = MutableStateFlow(fullCatalogue),
            query = MutableStateFlow(query),
            selectedSpeciesId = MutableStateFlow(preselectedSpeciesId),
            photo = MutableStateFlow(null),
            registering = MutableStateFlow(false),
            error = MutableStateFlow(null),
            preselectedSpeciesId = preselectedSpeciesId,
        ).first()
    }

    @Test
    fun `the results list is uncapped - every catalogue species is listed`() {
        val s = fullState()
        assertEquals(fullCatalogue.size, s.results.size)
        assertEquals("s-200", s.results.last().id)
    }

    @Test
    fun `arriving with a species preselected reports where it sits in the list`() {
        val s = fullState(preselectedSpeciesId = "s-137")
        assertEquals("the row is selected", "s-137", s.selected?.id)
        assertEquals("and its position is known", 136, s.preselectedIndex)
        assertEquals("s-137", s.results[s.preselectedIndex!!].id)
    }

    @Test
    fun `the preselected index follows the query, and is null once it is filtered away`() {
        val narrowed = fullState(query = "Species 13", preselectedSpeciesId = "s-137")
        assertEquals(
            "s-137",
            narrowed.results[narrowed.preselectedIndex!!].id,
        )
        assertNull(
            "a query that hides the species leaves nothing to scroll to",
            fullState(query = "Heron", preselectedSpeciesId = "s-137").preselectedIndex,
        )
    }

    @Test
    fun `a species the user taps themselves is not an arrival scroll`() {
        val s = fullState(preselectedSpeciesId = null)
        assertNull(s.preselectedIndex)
        // The same state function with a selection made on the screen: still nothing to scroll
        // to, because the list is already where the user's thumb put it.
        val tapped = runBlocking {
            registerUiState(
                species = MutableStateFlow(fullCatalogue),
                query = MutableStateFlow(""),
                selectedSpeciesId = MutableStateFlow("s-137"),
                photo = MutableStateFlow(null),
                registering = MutableStateFlow(false),
                error = MutableStateFlow(null),
            ).first()
        }
        assertEquals("s-137", tapped.selected?.id)
        assertNull(tapped.preselectedIndex)
    }
}
