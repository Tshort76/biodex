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

    // -----------------------------------------------------------------------
    // M21 over the plant fields (slice 12). Uses, the note and the kingdom are
    // fields like any other: edited by hand, they are the user's from then on.
    // -----------------------------------------------------------------------

    private val elder = SpeciesFields(
        commonName = "Blue Elderberry",
        scientificName = "Sambucus cerulea",
        kingdom = Kingdom.PLANT,
        taxClass = TaxClass.SHRUB,
        uses = setOf(PlantUse.MEDICINAL),
        usesNote = "Caution: recorded as poisonous in Duke's ethnobotanical database.",
        medicinalActivities = listOf("Diaphoretic", "Diuretic", "Laxative"),
        medicinalRecordCount = 60,
        usesAttribution = "Dr. Duke's Phytochemical and Ethnobotanical Databases · USDA ARS · CC0",
    )

    @Test
    fun `a hand-written note survives every later backfill`() {
        val mine = elder.copy(usesNote = "Berries in late summer — cook them. Caution: never raw.")

        val out = mergeLookup(
            existing = mine,
            lookup = LookupFields(
                usesNote = "Caution: recorded as poisonous in Duke's ethnobotanical database.",
                uses = setOf(PlantUse.MEDICINAL),
            ),
            userEdited = setOf(SpeciesField.USES_NOTE),
        )

        assertEquals("Berries in late summer — cook them. Caution: never raw.", out.usesNote)
    }

    @Test
    fun `a hand-set use tag survives, and an untouched one keeps tracking the index`() {
        val mine = elder.copy(uses = setOf(PlantUse.EDIBLE))

        // The user turned edible on and medicinal off. A later backfill re-derives medicinal
        // from Duke's and must not put it back.
        val out = mergeLookup(
            existing = mine,
            lookup = LookupFields(uses = setOf(PlantUse.MEDICINAL), medicinalRecordCount = 61),
            userEdited = setOf(SpeciesField.USES),
        )

        assertEquals(setOf(PlantUse.EDIBLE), out.uses)
        // …while the Duke's columns are source data and are not the user's to own.
        assertEquals(61, out.medicinalRecordCount)
    }

    @Test
    fun `an untouched use tag does track the newest lookup`() {
        val out = mergeLookup(
            existing = elder.copy(uses = emptySet()),
            lookup = LookupFields(uses = setOf(PlantUse.MEDICINAL)),
            userEdited = emptySet(),
        )

        assertEquals(setOf(PlantUse.MEDICINAL), out.uses)
    }

    @Test
    fun `a lookup with nothing to say never blanks a plant's uses`() {
        val out = mergeLookup(existing = elder, lookup = LookupFields(), userEdited = emptySet())

        assertEquals(elder.uses, out.uses)
        assertEquals(elder.usesNote, out.usesNote)
        assertEquals(60, out.medicinalRecordCount)
    }

    @Test
    fun `a hand-picked kingdom survives a backfill that still reads the other one`() {
        val corrected = elder.copy(kingdom = Kingdom.PLANT, taxClass = TaxClass.SHRUB)

        val out = previewFields(
            stored = corrected,
            lookup = LookupFields(kingdom = Kingdom.ANIMAL, taxClass = TaxClass.BIRD),
            lockedFields = setOf(SpeciesField.KINGDOM),
            editValues = null,
            editedNow = emptySet(),
        )

        assertEquals(Kingdom.PLANT, out.kingdom)
        // GBIF's bird class arrives unlocked, and the pairing rule sends it back to a plant
        // class rather than leaving a plant filed as a bird.
        assertEquals(TaxClass.SHRUB.kingdom, out.taxClass.kingdom)
    }

    @Test
    fun `toggling the kingdom takes the class to that kingdom's default`() {
        val toggled = previewFields(
            stored = SpeciesFields(commonName = "Salal", taxClass = TaxClass.BIRD),
            lookup = LookupFields(taxClass = TaxClass.BIRD),
            lockedFields = setOf(SpeciesField.KINGDOM),
            editValues = SpeciesFields(
                commonName = "Salal",
                kingdom = Kingdom.PLANT,
                taxClass = TaxClass.HERB,
            ),
            editedNow = setOf(SpeciesField.KINGDOM),
        )

        assertEquals(Kingdom.PLANT, toggled.kingdom)
        assertEquals(TaxClass.HERB, toggled.taxClass)
    }

    // -----------------------------------------------------------------------
    // The write-path invariants of 11.1, as a function anything can call.
    // -----------------------------------------------------------------------

    @Test
    fun `a note with no use tag and no caution is dropped`() {
        val out = elder.copy(uses = emptySet(), usesNote = "Berries in late summer.").normalized()

        assertNull("a description has nowhere to render without a tag", out.usesNote)
    }

    @Test
    fun `a caution survives with no use tags at all`() {
        // The exception the whole plant safety story rests on: a recorded toxicity is a fact
        // about the species, not a qualifier on a use the user happened to claim. The person
        // it protects tagged nothing and comes back months later.
        val out = elder.copy(
            uses = emptySet(),
            usesNote = "Caution: recorded as poisonous in Duke's ethnobotanical database.",
        ).normalized()

        assertEquals(
            "Caution: recorded as poisonous in Duke's ethnobotanical database.",
            out.usesNote,
        )
    }

    @Test
    fun `an untagged note is reduced to its caution and nothing else`() {
        val out = elder.copy(
            uses = emptySet(),
            usesNote = "Berries in late summer — cook them. Caution: raw berries are toxic.",
        ).normalized()

        assertEquals("Caution: raw berries are toxic.", out.usesNote)
    }

    @Test
    fun `a tagged note is kept whole, caution and all`() {
        val whole = "Berries in late summer — cook them. Caution: raw berries are toxic."
        val out = elder.copy(uses = setOf(PlantUse.EDIBLE), usesNote = whole).normalized()

        assertEquals(whole, out.usesNote)
    }

    @Test
    fun `an animal still carries no note, caution or not`() {
        val out = elder.copy(
            kingdom = Kingdom.ANIMAL,
            taxClass = TaxClass.BIRD,
            usesNote = "Caution: recorded as poisonous in Duke's ethnobotanical database.",
        ).normalized()

        assertNull(out.usesNote)
    }

    @Test
    fun `a hand-written caution survives a backfill that empties an untouched uses`() {
        // M21 keeps the note; the relaxed invariant is what stops 11.1 taking it back when the
        // tags it arrived beside go away.
        val out = previewFields(
            stored = elder.copy(usesNote = "Caution: the berries here are the red kind."),
            lookup = LookupFields(uses = emptySet(), medicinalActivities = emptyList()),
            lockedFields = setOf(SpeciesField.USES_NOTE),
            editValues = null,
            editedNow = emptySet(),
        )

        assertEquals(emptySet<PlantUse>(), out.uses)
        assertEquals("Caution: the berries here are the red kind.", out.usesNote)
    }

    @Test
    fun `a blank note is null, not an empty string`() {
        assertNull(elder.copy(usesNote = "   ").normalized().usesNote)
    }

    @Test
    fun `an animal carries no uses, no note and no Duke's columns`() {
        val out = elder.copy(kingdom = Kingdom.ANIMAL, taxClass = TaxClass.BIRD).normalized()

        assertEquals(emptySet<PlantUse>(), out.uses)
        assertNull(out.usesNote)
        assertEquals(emptyList<String>(), out.medicinalActivities)
        assertEquals(0, out.medicinalRecordCount)
        assertNull(out.usesAttribution)
    }

    @Test
    fun `a credit with no Duke's data behind it is dropped`() {
        val out = elder.copy(medicinalActivities = emptyList(), medicinalRecordCount = 0).normalized()

        assertNull(out.usesAttribution)
    }

    @Test
    fun `the kingdom wins over a class that does not belong to it`() {
        assertEquals(
            TaxClass.HERB,
            elder.copy(taxClass = TaxClass.BIRD).normalized().taxClass,
        )
        assertEquals(
            TaxClass.OTHER_INVERTEBRATE,
            elder.copy(kingdom = Kingdom.ANIMAL, taxClass = TaxClass.TREE).normalized().taxClass,
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

    @Test
    fun `the conifer silhouette is read only while the species is still a tree`() {
        val fir = SpeciesFields(
            commonName = "Douglas-fir",
            kingdom = Kingdom.PLANT,
            taxClass = TaxClass.TREE,
            silhouetteResOverride = "sil_tree_conifer",
        )

        assertEquals("sil_tree_conifer", fir.silhouetteRes)
        assertEquals("sil_shrub", fir.copy(taxClass = TaxClass.SHRUB).silhouetteRes)
        // A tree with no signal either way is a broadleaf; there is no `sil_tree`.
        assertEquals("sil_tree_broadleaf", fir.copy(silhouetteResOverride = null).silhouetteRes)
    }
}
