package dev.tlong.biodex.ui.register

import dev.tlong.biodex.domain.Kingdom
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
    ) = runBlocking {
        registerUiState(
            species = MutableStateFlow(catalogue),
            query = MutableStateFlow(query),
            selectedSpeciesId = MutableStateFlow(selectedId),
            photo = MutableStateFlow(photo),
            registering = MutableStateFlow(registering),
            error = MutableStateFlow(error),
        ).first()
    }

    private val photo = PickedPhoto("content://media/1", "IMG_1.jpg")

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
    fun `a name outside the catalogue is the add-your-own path, not an error`() {
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

    @Test
    fun `the results list is capped so the photo row and buttons stay reachable`() {
        val many = (1..80).map { species("s-$it", it, "Species $it", null) }
        val s = runBlocking {
            registerUiState(
                species = MutableStateFlow(many),
                query = MutableStateFlow(""),
                selectedSpeciesId = MutableStateFlow(null),
                photo = MutableStateFlow(null),
                registering = MutableStateFlow(false),
                error = MutableStateFlow(null),
            ).first()
        }
        assertEquals(REGISTER_RESULT_LIMIT, s.results.size)
    }

    // -----------------------------------------------------------------------
    // M08's hand-off into the user-added flow (slice 7).
    // -----------------------------------------------------------------------

    @Test
    fun `adding your own species needs both the name and the photo`() {
        assertFalse(state().canAddOwn)
        assertFalse("a name with no photo is not enough", state(query = "Varied Thrush").canAddOwn)
        assertFalse("a photo with no name is not enough", state(photo = photo).canAddOwn)
        assertTrue(state(query = "Varied Thrush", photo = photo).canAddOwn)
    }

    @Test
    fun `the button says which half is still missing`() {
        assertTrue(state().addOwnLabel.contains("Type a name"))
        assertTrue(state(query = "Varied Thrush").addOwnLabel.contains("Attach a photo"))
        assertEquals(
            "Add \u201CVaried Thrush\u201D as your own species \uFF0B",
            state(query = "Varied Thrush", photo = photo).addOwnLabel,
        )
    }
}
