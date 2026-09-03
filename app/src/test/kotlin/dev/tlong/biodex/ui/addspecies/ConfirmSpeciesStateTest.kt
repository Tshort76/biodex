package dev.tlong.biodex.ui.addspecies

import dev.tlong.biodex.data.catalogue.DukeRecord
import dev.tlong.biodex.data.net.CandidateDetails
import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.net.MatchKind
import dev.tlong.biodex.data.net.SpeciesCandidate
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.LookupFields
import dev.tlong.biodex.domain.SpeciesField
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.UserSpeciesRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        nextDexNumber: Int = 9001,
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
        val elk = SpeciesCandidate("Cervus elaphus", "Red Deer", taxClass = TaxClass.MAMMAL, matchKind = MatchKind.HIGHER_RANK)
        val other = SpeciesCandidate("Cervus canadensis", "Elk", taxClass = TaxClass.MAMMAL, matchKind = MatchKind.VERNACULAR_OTHER)

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
        val robin = SpeciesCandidate("Turdus migratorius", "American Robin", taxClass = TaxClass.BIRD, matchKind = MatchKind.VERNACULAR_OTHER)

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
        dexNumber = 9003,
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

    // -----------------------------------------------------------------------
    // The plant card (M19/M27): a growth-form pick and a uses editor where an
    // animal's call row is.
    // -----------------------------------------------------------------------

    private val madrone = SpeciesCandidate(
        scientificName = "Arbutus menziesii",
        commonName = "Pacific Madrone",
        kingdom = Kingdom.PLANT,
        taxClass = TaxClass.TREE,
        silhouetteResOverride = "sil_tree_broadleaf",
        matchKind = MatchKind.EXACT,
    )

    private fun plantDetails(
        uses: Set<PlantUse> = setOf(PlantUse.MEDICINAL),
        usesNote: String? = null,
        duke: DukeRecord? = DukeRecord(listOf("Astringent", "Diuretic", "Vulnerary"), 27, false),
    ) = CandidateDetails(
        fields = LookupFields(
            scientificName = "Arbutus menziesii",
            kingdom = Kingdom.PLANT,
            taxClass = TaxClass.TREE,
            silhouetteResOverride = "sil_tree_broadleaf",
            habitatText = "Dry, open slopes and bluffs near the coast.",
            uses = uses,
            usesNote = usesNote,
            medicinalActivities = duke?.activities.orEmpty(),
            medicinalRecordCount = duke?.recordCount ?: 0,
            usesAttribution = duke?.let { "Dr. Duke's · USDA ARS · CC0" },
        ),
        duke = duke,
        dukeConsulted = true,
    )

    private fun plantCard(
        details: CandidateDetails = plantDetails(),
        edits: ConfirmCardEdits = ConfirmCardEdits(),
    ) = card(
        outcome = LookupOutcome.Resolved(listOf(madrone), 0, details),
        details = details,
        edits = edits,
    )

    @Test
    fun `a plant card shows the kingdom beside the class and offers only plant forms`() {
        val state = plantCard()

        assertTrue(state.isPlant)
        assertEquals("Arbutus menziesii · plant · tree", state.identityLine)
        assertEquals(TaxClass.of(Kingdom.PLANT), state.offeredClasses)
        assertFalse("never offer 'bird' for a madrone", TaxClass.BIRD in state.offeredClasses)
    }

    @Test
    fun `the medicinal toggle defaults on for a species over the threshold`() {
        val state = plantCard()

        assertTrue(state.hasUse(PlantUse.MEDICINAL))
        // Edible is never defaulted on: the app does not assert edibility (D14, M30).
        assertFalse(state.hasUse(PlantUse.EDIBLE))
        assertEquals(
            "Duke's records 27 traditional uses: Astringent, Diuretic, Vulnerary",
            state.dukeLabel,
        )
    }

    @Test
    fun `a species under the threshold opens with the toggle off`() {
        val grape = plantCard(
            details = plantDetails(
                uses = emptySet(),
                duke = DukeRecord(listOf("Astringent", "Laxative"), 4, false),
            ),
        )

        assertFalse(grape.hasUse(PlantUse.MEDICINAL))
        assertTrue("the record is still shown, read-only", grape.dukeLabel.contains("4"))
    }

    @Test
    fun `no Duke's record says so plainly, and tags nothing`() {
        val state = plantCard(details = plantDetails(uses = emptySet(), duke = null))

        assertEquals("No Duke's record for this species", state.dukeLabel)
        assertTrue(state.uses.isEmpty())
        assertNull(state.fields.usesAttribution)
    }

    @Test
    fun `a poison record pre-fills the caution and the card renders it as a caution`() {
        val state = plantCard(
            details = plantDetails(
                usesNote = "Caution: recorded as poisonous in Duke's ethnobotanical database.",
                duke = DukeRecord(listOf("Diaphoretic", "Diuretic", "Laxative"), 60, true),
            ),
        )

        assertTrue(state.poisonRecorded)
        assertEquals(
            "Caution: recorded as poisonous in Duke's ethnobotanical database.",
            state.noteCaution,
        )
        assertEquals("", state.noteBody)
    }

    @Test
    fun `an untagged poisonous plant keeps its caution`() {
        val state = plantCard(
            details = plantDetails(
                uses = emptySet(),
                usesNote = "Caution: recorded as poisonous in Duke's ethnobotanical database.",
                duke = DukeRecord(listOf("Diuretic"), 6, true),
            ),
        )

        // Bracken is the shape: one Duke's activity, so no medicinal tag, but a Poison record.
        // The warning has to outlive the tag it arrived without, or the app is telling someone
        // nothing about a plant a source calls poisonous.
        assertTrue(state.uses.isEmpty())
        assertTrue(state.poisonRecorded)
        assertEquals(
            "Caution: recorded as poisonous in Duke's ethnobotanical database.",
            state.fields.usesNote,
        )
        assertEquals(state.fields.usesNote, state.noteCaution)
        assertFalse("nothing to type into until a tag exists", state.noteEditable)
    }

    @Test
    fun `toggling a use on the card claims it, and the note comes with it`() {
        val state = plantCard(
            edits = ConfirmCardEdits(
                values = SpeciesFields(
                    commonName = "Pacific Madrone",
                    kingdom = Kingdom.PLANT,
                    taxClass = TaxClass.TREE,
                    uses = setOf(PlantUse.EDIBLE),
                    usesNote = "Berries in autumn — mealy but edible.",
                ),
                editedFields = setOf(SpeciesField.USES, SpeciesField.USES_NOTE),
            ),
        )

        assertEquals(setOf(PlantUse.EDIBLE), state.uses)
        assertEquals("Berries in autumn — mealy but edible.", state.fields.usesNote)
        assertTrue(state.isEdited(SpeciesField.USES))
    }

    @Test
    fun `a plain note typed with no use tag cannot be saved, so the field is not offered`() {
        val state = plantCard(
            details = plantDetails(uses = emptySet(), duke = null),
            edits = ConfirmCardEdits(
                values = SpeciesFields(
                    commonName = "Pacific Madrone",
                    kingdom = Kingdom.PLANT,
                    taxClass = TaxClass.TREE,
                    usesNote = "Berries in autumn.",
                ),
                editedFields = setOf(SpeciesField.USES_NOTE),
            ),
        )

        // A description with no tag has nowhere to render, so the screen hides the editor
        // rather than showing a field that swallows every keystroke.
        assertTrue(state.uses.isEmpty())
        assertNull(state.fields.usesNote)
        assertFalse(state.noteEditable)
    }

    @Test
    fun `an animal card offers no uses editor`() {
        val state = card()

        assertFalse(state.isPlant)
        assertTrue(state.uses.isEmpty())
        assertEquals("Duke's index not consulted", state.dukeLabel)
        assertFalse(state.poisonRecorded)
    }

    @Test
    fun `the card is still loading while the lookup is in flight`() {
        val state = card(outcome = null, details = null)

        assertEquals("Varied Thrush", state.fields.commonName)
        assertTrue(state.candidates.isEmpty())
    }
    // -----------------------------------------------------------------------
    // M41: the photo a plant will not keep.
    // -----------------------------------------------------------------------

    @Test
    fun `a plant card warns that the photo is not kept`() {
        val plant = card(edits = ConfirmCardEdits(values = SpeciesFields(
            commonName = "Salal",
            kingdom = Kingdom.PLANT,
            taxClass = TaxClass.SHRUB,
        ), editedFields = setOf(SpeciesField.KINGDOM)))

        assertNotNull(plant.photoNotKeptWarning)
    }

    @Test
    fun `an animal card does not, and neither does a plant with no photo`() {
        assertNull(card().photoNotKeptWarning)

        val noPhoto = card(
            draft = draft.copy(photoUri = null),
            edits = ConfirmCardEdits(values = SpeciesFields(
                commonName = "Salal",
                kingdom = Kingdom.PLANT,
                taxClass = TaxClass.SHRUB,
            ), editedFields = setOf(SpeciesField.KINGDOM)),
        )
        assertNull(noPhoto.photoNotKeptWarning)
    }

}
