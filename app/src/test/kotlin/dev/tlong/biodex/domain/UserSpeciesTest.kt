package dev.tlong.biodex.domain

import dev.tlong.biodex.data.catalogue.pairKingdomAndClass
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
    fun `a backfill that found no classification leaves the stored one alone`() {
        val classified = thrush.copy(
            lineage = Lineage("Animalia", "Chordata", "Aves", "Passeriformes", "Turdidae"),
        )

        // A lookup always carries a Lineage; one that resolved nothing carries Unknown.
        val merged = mergeLookup(classified, LookupFields(lineage = Lineage.Unknown), emptySet())

        assertEquals("Turdidae", merged.lineage.family)
    }

    @Test
    fun `a backfill that found a classification fills an empty one`() {
        val found = Lineage("Animalia", "Chordata", "Aves", "Passeriformes", "Turdidae")

        val merged = mergeLookup(thrush, LookupFields(lineage = found), emptySet())

        assertEquals(found, merged.lineage)
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
        // catalogue's kingdom ranges rather than below them (ARCHITECTURE.md 11.1).
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
        assertEquals("sil_mushroom", chanterelle.silhouetteRes)
    }

    // -----------------------------------------------------------------------
    // M21 over the kingdom. Edited by hand, it is the user's from then on.
    // -----------------------------------------------------------------------

    private val chanterelle = SpeciesFields(
        commonName = "Golden Chanterelle",
        scientificName = "Cantharellus formosus",
        kingdom = Kingdom.FUNGUS,
        taxClass = TaxClass.MUSHROOM,
    )

    @Test
    fun `a hand-picked kingdom survives a backfill that still reads the other one`() {
        val out = previewFields(
            stored = chanterelle,
            lookup = LookupFields(kingdom = Kingdom.ANIMAL, taxClass = TaxClass.BIRD),
            lockedFields = setOf(SpeciesField.KINGDOM),
            editValues = null,
            editedNow = emptySet(),
        )

        assertEquals(Kingdom.FUNGUS, out.kingdom)
        // GBIF's bird class arrives unlocked, and the pairing rule sends it back to a fungal
        // class rather than leaving a fungus filed as a bird.
        assertEquals(Kingdom.FUNGUS, out.taxClass.kingdom)
    }

    @Test
    fun `toggling the kingdom takes the class to that kingdom's default`() {
        val toggled = previewFields(
            stored = SpeciesFields(commonName = "Chanterelle", taxClass = TaxClass.BIRD),
            lookup = LookupFields(taxClass = TaxClass.BIRD),
            lockedFields = setOf(SpeciesField.KINGDOM),
            editValues = SpeciesFields(
                commonName = "Chanterelle",
                kingdom = Kingdom.FUNGUS,
                taxClass = TaxClass.OTHER_FUNGUS,
            ),
            editedNow = setOf(SpeciesField.KINGDOM),
        )

        assertEquals(Kingdom.FUNGUS, toggled.kingdom)
        assertEquals(TaxClass.OTHER_FUNGUS, toggled.taxClass)
    }

    // -----------------------------------------------------------------------
    // The write-path invariants of 11.1, as a function anything can call.
    // -----------------------------------------------------------------------

    @Test
    fun `a user-added species carries no use and no note, whatever it arrived with`() {
        // The Food source tag is curated (D48) and no source pre-fills a caution any more
        // (D59), so both are cleared at the door for either kingdom — an old backup cannot
        // bring them back.
        val fungus = chanterelle.copy(
            uses = setOf(SpeciesUse.EDIBLE),
            usesNote = "Caution: only with a confident identification.",
        ).normalized()
        val animal = thrush.copy(uses = setOf(SpeciesUse.EDIBLE), usesNote = "Caution: none.").normalized()

        assertEquals(emptySet<SpeciesUse>(), fungus.uses)
        assertNull(fungus.usesNote)
        assertEquals(emptySet<SpeciesUse>(), animal.uses)
        assertNull(animal.usesNote)
    }

    @Test
    fun `the kingdom wins over a class that does not belong to it`() {
        assertEquals(
            TaxClass.OTHER_FUNGUS,
            chanterelle.copy(taxClass = TaxClass.BIRD).normalized().taxClass,
        )
        assertEquals(
            TaxClass.OTHER_INVERTEBRATE,
            chanterelle.copy(kingdom = Kingdom.ANIMAL, taxClass = TaxClass.MUSHROOM).normalized().taxClass,
        )
    }

    @Test
    fun `the pairing rule agrees with the one the importer and the backup import use`() {
        // Two implementations of one rule is exactly how they drift, so they are pinned
        // against each other rather than merely documented as the same.
        for (kingdom in Kingdom.entries) {
            for (taxClass in TaxClass.entries) {
                val mine = SpeciesFields(commonName = "x", kingdom = kingdom, taxClass = taxClass)
                    .normalized()
                val theirs = pairKingdomAndClass(kingdom.wireName, taxClass.wireName)
                assertEquals(theirs.first, mine.kingdom)
                assertEquals(theirs.second, mine.taxClass)
            }
        }
    }
}
