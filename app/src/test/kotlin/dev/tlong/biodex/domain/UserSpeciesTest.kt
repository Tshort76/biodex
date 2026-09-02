package dev.tlong.biodex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **M21: a hand-edited field survives a later backfill; untouched fields get filled in.**
 *
 * This is the subtlest invariant in slice 7 and the one no phone check can really prove — a
 * hand test can watch one field survive one re-lookup, where these can walk the whole cross
 * product. Written against the pure functions the write path and the confirm card both call.
 */
class UserSpeciesTest {

    private val thrush = SpeciesFields(
        commonName = "Varied Thrush",
        scientificName = "Ixoreus naevius",
        taxClass = TaxClass.BIRD,
        habitatText = "Dense coniferous forest.",
        description = "A thrush of the Pacific slope.",
        imageUrl = "https://example.org/thrush.jpg",
        imageAttribution = "Wikimedia Commons · CC BY-SA 4.0 · Someone",
        infoUrl = "https://en.wikipedia.org/wiki/Varied_thrush",
    )

    private val freshLookup = LookupFields(
        scientificName = "Ixoreus naevius",
        taxClass = TaxClass.BIRD,
        habitatText = "Breeds in moist coniferous forest from Alaska to California.",
        description = "A different lede.",
        imageUrl = "https://example.org/newer.jpg",
        imageAttribution = "Wikimedia Commons · CC BY 4.0 · Another",
        infoUrl = "https://en.wikipedia.org/wiki/Varied_thrush",
    )

    // -----------------------------------------------------------------------
    // The rule itself.
    // -----------------------------------------------------------------------

    @Test
    fun `a hand-edited field is never overwritten by a backfill`() {
        val mine = thrush.copy(habitatText = "The big fir behind the shed, most winters.")

        val merged = mergeLookup(mine, freshLookup, setOf(SpeciesField.HABITAT_TEXT))

        assertEquals("The big fir behind the shed, most winters.", merged.habitatText)
    }

    @Test
    fun `an untouched field takes the lookup's value, even when it already had one`() {
        val merged = mergeLookup(thrush, freshLookup, setOf(SpeciesField.HABITAT_TEXT))

        // Not merely a null-fill: an untouched field tracks the newest public data.
        assertEquals("A different lede.", merged.description)
        assertEquals("https://example.org/newer.jpg", merged.imageUrl)
    }

    @Test
    fun `a source that found nothing does not blank a field that has a value`() {
        val merged = mergeLookup(thrush, LookupFields(), emptySet())

        assertEquals(thrush, merged)
    }

    @Test
    fun `a field the user edited stays theirs across two successive backfills`() {
        val edited = setOf(SpeciesField.HABITAT_TEXT)
        val mine = thrush.copy(habitatText = "The big fir behind the shed.")

        val once = mergeLookup(mine, freshLookup, edited)
        val twice = mergeLookup(
            once,
            freshLookup.copy(habitatText = "Something else entirely.", description = "Third lede."),
            edited,
        )

        assertEquals("The big fir behind the shed.", twice.habitatText)
        // …while everything they never touched keeps moving with the source.
        assertEquals("Third lede.", twice.description)
    }

    @Test
    fun `the common name is the user's and no lookup can take it`() {
        val merged = mergeLookup(
            thrush.copy(commonName = "The shed thrush"),
            freshLookup,
            emptySet(),
        )

        assertEquals("The shed thrush", merged.commonName)
    }

    @Test
    fun `a credit line never outlives the image it credits`() {
        val mine = thrush.copy(
            imageUrl = "content://my/own/photo.jpg",
            imageAttribution = null,
        )

        val merged = mergeLookup(mine, freshLookup, setOf(SpeciesField.IMAGE_URL))

        assertEquals("content://my/own/photo.jpg", merged.imageUrl)
        assertNull("Commons must not be credited for a photo it did not supply", merged.imageAttribution)
    }

    @Test
    fun `every editable field can be locked, and locking all of them makes a backfill a no-op`() {
        val merged = mergeLookup(thrush, freshLookup, SpeciesField.editable.toSet())

        assertEquals(thrush, merged)
    }

    // -----------------------------------------------------------------------
    // The overlay the confirm card types into.
    // -----------------------------------------------------------------------

    @Test
    fun `hand edits apply only to the fields they name`() {
        val typed = thrush.copy(habitatText = "Mine.", description = "Also mine.")

        val out = applyFieldEdits(thrush, typed, listOf(SpeciesField.HABITAT_TEXT))

        assertEquals("Mine.", out.habitatText)
        assertEquals("A thrush of the Pacific slope.", out.description)
    }

    @Test
    fun `swapping candidate keeps the edit and takes the new species' other fields`() {
        val typed = thrush.copy(habitatText = "Mine.")
        val otherSpecies = LookupFields(
            scientificName = "Turdus migratorius",
            taxClass = TaxClass.BIRD,
            habitatText = "Lawns and woodland edges.",
            description = "The American robin.",
        )

        val preview = previewFields(
            stored = SpeciesFields(commonName = "Varied Thrush"),
            lookup = otherSpecies,
            lockedFields = setOf(SpeciesField.HABITAT_TEXT),
            editValues = typed,
            editedNow = setOf(SpeciesField.HABITAT_TEXT),
        )

        assertEquals("Mine.", preview.habitatText)
        assertEquals("Turdus migratorius", preview.scientificName)
        assertEquals("The American robin.", preview.description)
    }

    // -----------------------------------------------------------------------
    // The details-pending lifecycle (M20) and U-numbers (M02).
    // -----------------------------------------------------------------------

    @Test
    fun `pending is exactly no resolved scientific name`() {
        assertTrue(detailsPendingFor(SpeciesFields(commonName = "Something I saw")))
        assertTrue(detailsPendingFor(SpeciesFields(commonName = "x", scientificName = "  ")))
        assertFalse(detailsPendingFor(thrush))
    }

    @Test
    fun `the first user species is U01 and they climb from there`() {
        // The base moved from 1000 to 9000 with BioDex, so that user numbers sit above the
        // plant range rather than below it (ARCHITECTURE.md 11.1).
        assertEquals(9001, nextUserDexNumber(null))
        assertEquals("U01", displayDexNumber(9001, SpeciesSource.USER, Kingdom.ANIMAL))
        assertEquals(9002, nextUserDexNumber(9001))
        assertEquals(
            "U04",
            displayDexNumber(nextUserDexNumber(9003), SpeciesSource.USER, Kingdom.ANIMAL),
        )
    }

    @Test
    fun `a user species' silhouette follows its class`() {
        assertEquals("sil_bird", thrush.silhouetteRes)
        assertEquals("sil_other_invertebrate", SpeciesFields(commonName = "?").silhouetteRes)
    }
}
