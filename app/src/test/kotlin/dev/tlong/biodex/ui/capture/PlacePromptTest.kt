package dev.tlong.biodex.ui.capture

import dev.tlong.biodex.domain.GazetteerPlace
import dev.tlong.biodex.domain.PlaceAnswer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D68. The "Where was this?" prompt's state, shared by the entry and Identify screens. */
class PlacePromptTest {

    @Test
    fun `the place prompt offers the list and writes the list's spelling when it matches`() {
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

        assertEquals(
            listOf("Bear Valley, California", "Bear Valley Trail, California"),
            search("bear").suggestions,
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
}
