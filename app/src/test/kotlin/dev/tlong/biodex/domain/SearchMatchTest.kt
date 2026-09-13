package dev.tlong.biodex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M14 as revised by D54: the search forgives what a thumb on a phone keyboard gets wrong —
 * case, accents, punctuation, a missing or wrong letter — while a short query stays exact so
 * three letters do not match a third of the catalogue.
 */
class SearchMatchTest {

    @Test
    fun `folding drops case, accents and everything that is not a letter or digit`() {
        assertEquals("redtailedhawk", SearchMatch.fold("Red-tailed Hawk"))
        assertEquals("plntnet", SearchMatch.fold("Pl@ntNet"))
        assertEquals("cafe", SearchMatch.fold("Café"))
        assertEquals("", SearchMatch.fold("  · — "))
    }

    @Test
    fun `an empty query matches everything`() {
        assertTrue(SearchMatch.matches("Canada Goose", ""))
        assertTrue(SearchMatch.matches("Canada Goose", "   "))
    }

    @Test
    fun `exact substring still matches regardless of case, hyphens and spaces`() {
        assertTrue(SearchMatch.matches("Red-tailed Hawk", "red tailed"))
        assertTrue(SearchMatch.matches("Red-tailed Hawk", "REDTAILED"))
        assertTrue(SearchMatch.matches("Western Screech-Owl", "screechowl"))
        assertTrue(SearchMatch.matches("Megascops kennicottii", "Kennicott"))
    }

    @Test
    fun `a missing letter is forgiven once the query is five characters long`() {
        assertTrue("dropped letter", SearchMatch.matches("Canada Goose", "canda"))
        assertTrue("wrong letter", SearchMatch.matches("Canada Goose", "gouse"))
        assertTrue("extra letter", SearchMatch.matches("Canada Goose", "goosse"))
        assertTrue("swapped pair", SearchMatch.matches("Canada Goose", "gosoe"))
        assertTrue("doubled letter dropped", SearchMatch.matches("Sea Otter", "seaoter"))
    }

    @Test
    fun `two edits are allowed from nine characters`() {
        assertTrue(SearchMatch.matches("Western Fence Lizard", "westrn fenc"))
        assertFalse(
            "three edits is too many",
            SearchMatch.matches("Western Fence Lizard", "wstrn fnce lzrd"),
        )
    }

    @Test
    fun `short queries stay exact`() {
        assertTrue(SearchMatch.matches("Great Blue Heron", "blue"))
        assertFalse("four letters, one wrong", SearchMatch.matches("Great Blue Heron", "blux"))
        assertFalse("owl must not fuzz to ow", SearchMatch.matches("Cow Parsnip", "owl"))
    }

    @Test
    fun `the budget follows the folded length`() {
        assertEquals(0, SearchMatch.editBudget(4))
        assertEquals(1, SearchMatch.editBudget(5))
        assertEquals(1, SearchMatch.editBudget(8))
        assertEquals(2, SearchMatch.editBudget(9))
    }

    @Test
    fun `approximate containment is a substring test, not a whole-string distance`() {
        assertTrue(SearchMatch.approximatelyContains("californiasealion", "sealion", 0))
        assertTrue(SearchMatch.approximatelyContains("californiasealion", "seelion", 1))
        assertFalse(SearchMatch.approximatelyContains("californiasealion", "seelien", 1))
        assertTrue("pattern longer than text", SearchMatch.approximatelyContains("ab", "abc", 1))
        assertFalse(SearchMatch.approximatelyContains("", "abc", 2))
        assertTrue(SearchMatch.approximatelyContains("anything", "", 0))
    }
}
