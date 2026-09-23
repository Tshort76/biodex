package dev.tlong.biodex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D68: the place prompt takes a place off an offline list rather than free text, and this is
 * the list's search. Two sources, one ranking — the places this collection already uses come
 * first for an equally good match, because a life list returns to the same handful of them.
 */
class PlaceSuggestTest {

    private val gazetteer = listOf(
        GazetteerPlace("Point Reyes Station", "CA", 0),
        GazetteerPlace("Point Reyes Hill", "CA", 2),
        GazetteerPlace("Bear Valley", "CA", 0),
        GazetteerPlace("Bear Valley Trail", "CA", 1),
        GazetteerPlace("Cañada de los Osos", "CA", 2),
        GazetteerPlace("Astoria", "OR", 0),
        GazetteerPlace("Bear Valley", "WA", 2),
    )

    @Test
    fun `a line of the asset parses into a place, and a broken one into nothing`() {
        assertEquals(
            GazetteerPlace("Bear Valley", "CA", 0),
            parseGazetteerLine("Bear Valley\tCA\t0"),
        )
        assertNull(parseGazetteerLine("Bear Valley\tCA"))
        assertNull(parseGazetteerLine("Bear Valley\tCA\tzero"))
        assertNull(parseGazetteerLine("\tCA\t0"))
        assertNull(parseGazetteerLine(""))
    }

    @Test
    fun `a place reads as the geocoder would have written it`() {
        assertEquals("Bear Valley, California", GazetteerPlace("Bear Valley", "CA", 0).label)
        assertEquals("Astoria, Oregon", GazetteerPlace("Astoria", "OR", 0).label)
    }

    @Test
    fun `folding keeps the word boundaries that folding a species name throws away`() {
        assertEquals("point reyes station", foldPlace("Point Reyes Station"))
        assertEquals("canada de los osos", foldPlace("Cañada de los Osos"))
        assertEquals("obrien", foldPlace("O'Brien"))
    }

    @Test
    fun `an empty query offers the places this collection already uses`() {
        val recent = listOf("Bear Valley, California", "Tomales Bay, California")
        assertEquals(recent, suggestPlaces("", gazetteer, recent))
        assertEquals(recent, suggestPlaces("   ", gazetteer, recent))
    }

    @Test
    fun `a name at the front of a place outranks the same name inside one`() {
        val suggestions = suggestPlaces("bear", gazetteer, recent = emptyList())
        assertEquals("Bear Valley, California", suggestions.first())
        assertTrue(suggestions.contains("Bear Valley Trail, California"))
    }

    @Test
    fun `a word inside a name matches, and the town outranks the hill`() {
        val suggestions = suggestPlaces("reyes", gazetteer, recent = emptyList())
        assertEquals(
            listOf("Point Reyes Station, California", "Point Reyes Hill, California"),
            suggestions,
        )
    }

    @Test
    fun `a place already in the collection comes before the same match from the asset`() {
        val recent = listOf("Bear Valley Trail, California")
        val suggestions = suggestPlaces("bear valley t", gazetteer, recent)
        assertEquals("Bear Valley Trail, California", suggestions.first())
        // And it is offered once, not once per source.
        assertEquals(1, suggestions.count { it == "Bear Valley Trail, California" })
    }

    @Test
    fun `one name in two states is two places`() {
        val suggestions = suggestPlaces("bear valley", gazetteer, recent = emptyList())
        assertTrue(suggestions.contains("Bear Valley, California"))
        assertTrue(suggestions.contains("Bear Valley, Washington"))
    }

    @Test
    fun `accents and case do not have to be typed`() {
        assertEquals(
            listOf("Cañada de los Osos, California"),
            suggestPlaces("CANADA DE LOS", gazetteer, recent = emptyList()),
        )
    }

    @Test
    fun `a name nothing holds offers nothing`() {
        assertEquals(emptyList<String>(), suggestPlaces("zzzz", gazetteer, recent = emptyList()))
    }

    @Test
    fun `the list is capped, so a common word cannot fill the dialog`() {
        val many = (1..50).map { GazetteerPlace("City Park $it", "CA", 1) }
        assertEquals(3, suggestPlaces("city park", many, recent = emptyList(), limit = 3).size)
    }

    @Test
    fun `only a place off one of the two lists is a place`() {
        val recent = listOf("The back garden")
        assertEquals(
            "Bear Valley, California",
            canonicalPlace("Bear Valley, California", gazetteer, recent),
        )
        assertEquals(
            "a place already used is a place",
            "The back garden",
            canonicalPlace("The back garden", gazetteer, recent),
        )
        assertNull("the bare name is not the label", canonicalPlace("Bear Valley", gazetteer, recent))
        assertNull(canonicalPlace("Somewhere near the creek", gazetteer, recent))
        assertNull(canonicalPlace("", gazetteer, recent))
    }

    @Test
    fun `what lands on the sighting is the list's spelling, not what was typed`() {
        // The match forgives case, accents and spacing — so the label has to come from the
        // list, or the prompt would validate the typing and then store it anyway.
        assertEquals(
            "Bear Valley, California",
            canonicalPlace("bear valley,   california", gazetteer, recent = emptyList()),
        )
        assertEquals(
            "Cañada de los Osos, California",
            canonicalPlace("CANADA DE LOS OSOS, california", gazetteer, recent = emptyList()),
        )
    }
}
