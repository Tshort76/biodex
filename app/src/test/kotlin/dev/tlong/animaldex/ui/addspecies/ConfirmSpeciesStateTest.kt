package dev.tlong.animaldex.ui.addspecies

import dev.tlong.animaldex.data.net.CandidateDetails
import dev.tlong.animaldex.data.net.LookupOutcome
import dev.tlong.animaldex.data.net.MatchKind
import dev.tlong.animaldex.data.net.SpeciesCandidate
import dev.tlong.animaldex.domain.Ecosystem
import dev.tlong.animaldex.domain.LookupFields
import dev.tlong.animaldex.domain.SpeciesField
import dev.tlong.animaldex.domain.SpeciesFields
import dev.tlong.animaldex.domain.TaxClass
import dev.tlong.animaldex.domain.UserSpeciesRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What frame 6 shows for each combination of found and missing fields (section 8's "what the
 * confirm card's state should be given each combination"). The screen renders this and decides
 * nothing of its own.
 */
class ConfirmSpeciesStateTest {

    private val draft = AddSpeciesDraft(id = "d1", typedName = "Varied Thrush", photoUri = "content://p/1")

    private val thrush = SpeciesCandidate(
        scientificName = "Ixoreus naevius",
        commonName = "Varied Thrush",
        taxClass = TaxClass.BIRD,
        confidence = 100,
        matchKind = MatchKind.VERNACULAR_EXACT,
    )

    private val ecosystems = listOf(
        Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
        Ecosystem("urban-suburban", "pacific", "Urban & Suburban", 7),
    )

    private fun fullDetails() = CandidateDetails(
        fields = LookupFields(
            scientificName = "Ixoreus naevius",
            taxClass = TaxClass.BIRD,
            habitatText = "Breeds in dense, moist coniferous forest.",
            description = "A thrush of the Pacific slope.",
            imageUrl = "https://example.org/thrush.jpg",
            imageAttribution = "Wikimedia Commons · CC BY-SA 4.0 · Someone",
            infoUrl = "https://en.wikipedia.org/wiki/Varied_thrush",
        ),
        habitatSource = "wikipedia:section:Distribution and habitat",
    )

    private fun card(
        outcome: LookupOutcome? = LookupOutcome.Resolved(listOf(thrush), 0, fullDetails()),
        details: CandidateDetails? = fullDetails(),
        existing: UserSpeciesRecord? = null,
        edits: ConfirmCardEdits = ConfirmCardEdits(),
        draft: AddSpeciesDraft = this.draft,
        nextDexNumber: Int = 1001,
    ) = confirmCardState(
        draft = draft,
        outcome = outcome,
        details = details,
        existing = existing,
        edits = edits,
        ecosystems = ecosystems,
        nextDexNumber = nextDexNumber,
    )

    // -----------------------------------------------------------------------
    // The populated card (the mockup's frame 6).
    // -----------------------------------------------------------------------

    @Test
    fun `everything found renders the mockup's card`() {
        val state = card()

        assertEquals("Ixoreus naevius", state.fields.scientificName)
        assertEquals(TaxClass.BIRD, state.fields.taxClass)
        assertTrue(state.imageFound)
        assertTrue(state.habitatFound)
        assertEquals("Habitat · Wikipedia", state.habitatLabel)
        assertEquals("U01", state.dexLabel)
        assertEquals("Add to my dex — U01 Varied Thrush", state.acceptLabel)
        assertFalse(state.willBeDetailsPending)
    }

    @Test
    fun `no call found is the ordinary state, not an error`() {
        // Today this is every species: there is no Xeno-canto key (ARCHITECTURE.md 5.4).
        val state = card()

        assertFalse(state.callFound)
        assertTrue(state.callRowLabel.startsWith("No call found"))
        assertFalse(state.lookupFailed)
    }

    @Test
    fun `a found call names its source`() {
        val state = card(
            details = fullDetails().let {
                it.copy(
                    fields = it.fields.copy(
                        callUrl = "https://xeno-canto.org/222222/download",
                        callAttribution = "Xeno-canto XC222222 · CC BY-NC · A. Recordist",
                    ),
                )
            },
        )

        assertTrue(state.callFound)
        assertEquals("✓ Call found", state.callRowLabel)
        assertEquals("Xeno-canto XC222222 · CC BY-NC · A. Recordist", state.callAttribution)
    }

    @Test
    fun `no image found leaves the silhouette standing in`() {
        val state = card(details = fullDetails().let { it.copy(fields = it.fields.copy(imageUrl = null)) })

        assertFalse(state.imageFound)
        assertEquals("sil_bird", state.fields.silhouetteRes)
    }

    @Test
    fun `a lede fallback says which fallback it used`() {
        val state = card(details = fullDetails().copy(habitatSource = "wikipedia:lede"))

        assertEquals("Habitat · Wikipedia summary", state.habitatLabel)
    }

    @Test
    fun `no habitat text at all still renders the section`() {
        val state = card(
            details = CandidateDetails(fields = LookupFields(scientificName = "Ixoreus naevius")),
        )

        assertFalse(state.habitatFound)
        assertEquals("Habitat", state.habitatLabel)
        // Still acceptable: a resolved identity is what M20's pending flag reads.
        assertFalse(state.willBeDetailsPending)
        assertTrue(state.canAccept)
    }

    // -----------------------------------------------------------------------
    // Alternatives — M19's "other matches" affordance, which is not decoration.
    // -----------------------------------------------------------------------

    @Test
    fun `alternatives are counted and offered`() {
        val elk = SpeciesCandidate("Cervus elaphus", "Red Deer", TaxClass.MAMMAL, matchKind = MatchKind.HIGHER_RANK)
        val other = SpeciesCandidate("Cervus canadensis", "Elk", TaxClass.MAMMAL, matchKind = MatchKind.VERNACULAR_OTHER)

        val state = card(outcome = LookupOutcome.Resolved(listOf(elk, other), 0, fullDetails()))

        assertEquals("Not this one? 1 other match ›", state.alternativesLabel)
        assertEquals(listOf(other), state.alternatives)
    }

    @Test
    fun `a single candidate offers no alternatives link`() {
        assertNull(card().alternativesLabel)
    }

    @Test
    fun `picking a different candidate shows that candidate`() {
        val robin = SpeciesCandidate("Turdus migratorius", "American Robin", TaxClass.BIRD, matchKind = MatchKind.VERNACULAR_OTHER)

        val state = card(
            outcome = LookupOutcome.Resolved(listOf(thrush, robin), 0, fullDetails()),
            edits = ConfirmCardEdits(selectedIndex = 1),
        )

        assertEquals(robin, state.selectedCandidate)
        assertEquals(listOf(thrush), state.alternatives)
    }

    // -----------------------------------------------------------------------
    // The degraded paths (M20).
    // -----------------------------------------------------------------------

    @Test
    fun `a lookup that could not be made offers a details-pending save`() {
        val state = card(outcome = LookupOutcome.Failed("offline"), details = null)

        assertTrue(state.lookupFailed)
        assertFalse(state.noMatch)
        assertNull(state.fields.scientificName)
        assertTrue(state.willBeDetailsPending)
        assertEquals("Add to my dex — U01 Varied Thrush (details pending)", state.acceptLabel)
        assertTrue(state.canAccept)
    }

    @Test
    fun `a name GBIF does not know is a no-match, distinct from a failure`() {
        val state = card(outcome = LookupOutcome.NoMatch, details = null)

        assertTrue(state.noMatch)
        assertFalse(state.lookupFailed)
        assertTrue(state.willBeDetailsPending)
    }

    // -----------------------------------------------------------------------
    // Ecosystems: the one field no API supplies (D10).
    // -----------------------------------------------------------------------

    @Test
    fun `ecosystems start empty and are the user's to pick`() {
        val state = card()

        assertEquals(ecosystems, state.ecosystems)
        assertTrue(state.selectedEcosystemIds.isEmpty())
        assertTrue("an untagged species is still acceptable", state.canAccept)
    }

    @Test
    fun `picked ecosystems are what the accept button will write`() {
        val state = card(edits = ConfirmCardEdits(ecosystemIds = setOf("coastal-rainforest")))

        assertEquals(setOf("coastal-rainforest"), state.selectedEcosystemIds)
    }

    // -----------------------------------------------------------------------
    // The backfill card (M20/M21) — the same card, over an existing entry.
    // -----------------------------------------------------------------------

    private fun pendingRecord() = UserSpeciesRecord(
        id = "user-1",
        regionId = "pacific",
        dexNumber = 1003,
        detailsPending = true,
        fields = SpeciesFields(commonName = "Varied Thrush"),
    )

    @Test
    fun `a backfill card keeps the entry's own U-number and changes the button`() {
        val state = card(
            draft = draft.copy(photoUri = null, backfillSpeciesId = "user-1"),
            existing = pendingRecord(),
        )

        assertTrue(state.isBackfill)
        assertEquals("U03", state.dexLabel)
        assertEquals("Save these details", state.acceptLabel)
        assertEquals("Ixoreus naevius", state.fields.scientificName)
    }

    @Test
    fun `a backfill card shows the user's stored edit, not the lookup's version`() {
        val edited = pendingRecord().let {
            it.copy(
                fields = it.fields.copy(habitatText = "The big fir behind the shed."),
                userEditedFields = listOf(SpeciesField.HABITAT_TEXT),
            )
        }

        val state = card(
            draft = draft.copy(photoUri = null, backfillSpeciesId = "user-1"),
            existing = edited,
        )

        assertEquals("The big fir behind the shed.", state.fields.habitatText)
        assertTrue(state.isEdited(SpeciesField.HABITAT_TEXT))
        // …and everything untouched still fills in.
        assertEquals("Ixoreus naevius", state.fields.scientificName)
        assertEquals("A thrush of the Pacific slope.", state.fields.description)
    }

    @Test
    fun `an edit made on the card is marked and preserved in the preview`() {
        val state = card(
            edits = ConfirmCardEdits(
                values = SpeciesFields(commonName = "Varied Thrush", habitatText = "Mine."),
                editedFields = setOf(SpeciesField.HABITAT_TEXT),
            ),
        )

        assertEquals("Mine.", state.fields.habitatText)
        assertTrue(state.isEdited(SpeciesField.HABITAT_TEXT))
        assertFalse(state.isEdited(SpeciesField.DESCRIPTION))
    }

    @Test
    fun `the card is still loading while the lookup is in flight`() {
        val state = card(outcome = null, details = null)

        assertEquals("Varied Thrush", state.fields.commonName)
        assertTrue(state.candidates.isEmpty())
    }
}
