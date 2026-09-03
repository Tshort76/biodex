package dev.tlong.biodex.ui

import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.UsesNote
import dev.tlong.biodex.ui.common.USES_DISCLAIMER
import dev.tlong.biodex.ui.common.dukesLine
import dev.tlong.biodex.ui.common.usesDisclaimer
import dev.tlong.biodex.ui.detail.entryDetailUiState
import dev.tlong.biodex.ui.grid.CaughtFilter
import dev.tlong.biodex.ui.grid.DexGridFilters
import dev.tlong.biodex.ui.grid.classChips
import dev.tlong.biodex.ui.grid.dexGridUiState
import dev.tlong.biodex.ui.grid.filterSpecies
import dev.tlong.biodex.ui.reveal.RevealContent
import dev.tlong.biodex.ui.reveal.revealCounterLabel
import dev.tlong.biodex.ui.stats.buildStatsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 11's done-check (ARCHITECTURE.md 11.6), run against the two-kingdom fixture so it
 * holds before slice 10's asset lands.
 *
 * What is worth pinning here is what a person would notice on the phone and what a person
 * could be hurt by: a plant never producing a call row, a use chip never returning an animal,
 * the uses section's order and the disclaimer's exact words, the stats grouping by kingdom,
 * and — the regression guard for the whole expansion — that every animal number is what it
 * was before plants existed.
 */
class PlantUiTest {

    private val all = TwoKingdomFixture.summaries(caught = setOf("steller-jay", "blue-elderberry"))

    // ---- the grid's chips (M23) -----------------------------------------------------

    @Test
    fun `the edible filter never returns an animal`() {
        val edible = filterSpecies(all, "", DexGridFilters(use = PlantUse.EDIBLE))

        assertTrue(edible.isNotEmpty())
        assertTrue(edible.all { it.kingdom == Kingdom.PLANT })
        assertEquals(
            listOf("pacific-madrone", "blue-elderberry", "miners-lettuce"),
            edible.map { it.id }.sortedBy { id -> all.first { it.id == id }.dexNumber },
        )
    }

    @Test
    fun `a medicinal-only plant does not answer the edible chip`() {
        val edible = filterSpecies(all, "", DexGridFilters(use = PlantUse.EDIBLE)).map { it.id }
        val medicinal = filterSpecies(all, "", DexGridFilters(use = PlantUse.MEDICINAL))
            .map { it.id }

        assertFalse("common-yarrow" in edible)
        assertTrue("common-yarrow" in medicinal)
        // Tagged both, so it answers either chip.
        assertTrue("blue-elderberry" in edible && "blue-elderberry" in medicinal)
    }

    @Test
    fun `a plant with no uses answers no use chip`() {
        PlantUse.entries.forEach { use ->
            val ids = filterSpecies(all, "", DexGridFilters(use = use)).map { it.id }
            assertFalse("douglas-fir must not answer $use", "douglas-fir" in ids)
            assertFalse("western-sword-fern must not answer $use", "western-sword-fern" in ids)
        }
    }

    @Test
    fun `the kingdom chip splits the catalogue and the dimensions compose`() {
        val plants = filterSpecies(all, "", DexGridFilters(kingdom = Kingdom.PLANT))
        assertEquals(8, plants.size)
        assertEquals(10, filterSpecies(all, "", DexGridFilters(kingdom = Kingdom.ANIMAL)).size)

        val caughtPlants = filterSpecies(
            all,
            "",
            DexGridFilters(kingdom = Kingdom.PLANT, caught = CaughtFilter.CAUGHT),
        )
        assertEquals(listOf("blue-elderberry"), caughtPlants.map { it.id })
    }

    @Test
    fun `class chips follow the selected kingdom`() {
        assertEquals(TaxClass.entries, classChips(DexGridFilters()))

        val plantChips = classChips(DexGridFilters(kingdom = Kingdom.PLANT))
        assertEquals(listOf(TaxClass.TREE, TaxClass.SHRUB, TaxClass.HERB, TaxClass.FERN), plantChips)
        assertTrue(classChips(DexGridFilters(kingdom = Kingdom.ANIMAL)).none {
            it.kingdom == Kingdom.PLANT
        })
    }

    @Test
    fun `the row offers no plant class chip while the region has no plants`() {
        // The bug slice 9 left: the shipped 120-animal catalogue rendered Trees / Shrubs /
        // Herbs / Ferns, four chips whose only possible result was an empty grid.
        val animalsOnly = all.filter { it.kingdom == Kingdom.ANIMAL }
        val state = runBlocking {
            dexGridUiState(
                species = MutableStateFlow(animalsOnly),
                ecosystems = MutableStateFlow(TwoKingdomFixture.ecosystems),
                progress = MutableStateFlow(TwoKingdomFixture.progress(animalsOnly)),
                query = MutableStateFlow(""),
                filters = MutableStateFlow(DexGridFilters()),
            ).first()
        }

        assertFalse(state.showKingdomChips)
        assertTrue(classChips(state.filters, state.availableClasses).none {
            it.kingdom == Kingdom.PLANT
        })

        // With plants in the region every chip is back.
        val both = runBlocking {
            dexGridUiState(
                species = MutableStateFlow(all),
                ecosystems = MutableStateFlow(TwoKingdomFixture.ecosystems),
                progress = MutableStateFlow(TwoKingdomFixture.progress(all)),
                query = MutableStateFlow(""),
                filters = MutableStateFlow(DexGridFilters()),
            ).first()
        }
        assertTrue(both.showKingdomChips)
        // Not `TaxClass.entries`: this row offers only the classes the region actually holds,
        // which is the whole point of the slice 9 fix above, and this fixture has two
        // kingdoms and no fungi. Comparing against `entries` was right only for as long as
        // every class in the enum happened to be in the fixture.
        val nonFungal = TaxClass.entries.filter { it.kingdom != Kingdom.FUNGUS }
        assertEquals(nonFungal, classChips(both.filters, both.availableClasses))
    }

    @Test
    fun `the row offers no kingdom chip for a kingdom the region does not hold`() {
        // The slice 9 bug, arriving a second time through a different door: adding FUNGUS to
        // the enum put a Fungi chip on a catalogue with no fungi in it, whose only possible
        // result was an empty grid. Kingdom chips come from the data, not from the enum.
        val state = runBlocking {
            dexGridUiState(
                species = MutableStateFlow(all),
                ecosystems = MutableStateFlow(TwoKingdomFixture.ecosystems),
                progress = MutableStateFlow(TwoKingdomFixture.progress(all)),
                query = MutableStateFlow(""),
                filters = MutableStateFlow(DexGridFilters()),
            ).first()
        }
        assertEquals(setOf(Kingdom.ANIMAL, Kingdom.PLANT), state.availableKingdoms)
        assertFalse(Kingdom.FUNGUS in state.availableKingdoms)
        assertTrue(state.showKingdomChips)
    }

    @Test
    fun `one kingdom alone is offered no kingdom chips at all`() {
        val animalsOnly = all.filter { it.kingdom == Kingdom.ANIMAL }
        val state = runBlocking {
            dexGridUiState(
                species = MutableStateFlow(animalsOnly),
                ecosystems = MutableStateFlow(TwoKingdomFixture.ecosystems),
                progress = MutableStateFlow(TwoKingdomFixture.progress(animalsOnly)),
                query = MutableStateFlow(""),
                filters = MutableStateFlow(DexGridFilters()),
            ).first()
        }
        assertEquals(setOf(Kingdom.ANIMAL), state.availableKingdoms)
        assertFalse(state.showKingdomChips)
        assertFalse(state.showUseChips)
    }

    @Test
    fun `a selected class chip survives a kingdom that excludes it`() {
        // Tapping Trees and then Animals must not hide the Trees chip: the grid would be
        // empty with no visible filter to un-tap.
        val chips = classChips(DexGridFilters(kingdom = Kingdom.ANIMAL, taxClass = TaxClass.TREE))
        assertTrue(TaxClass.TREE in chips)
    }

    // ---- the detail screen's slot (M24, D15) ----------------------------------------

    private fun detailState(
        id: String,
        online: Boolean = true,
    ) = runBlocking {
        entryDetailUiState(
            detail = MutableStateFlow(TwoKingdomFixture.detail(id)),
            ecosystems = MutableStateFlow(TwoKingdomFixture.ecosystems),
            captures = MutableStateFlow(emptyList()),
            progress = MutableStateFlow(TwoKingdomFixture.progress(all)),
            online = MutableStateFlow(online),
        ).first()
    }

    @Test
    fun `an animal shows no uses section`() {
        assertNull(detailState("western-screech-owl").uses)
        assertNull(detailState("steller-jay").uses)
    }

    @Test
    fun `a plant with nothing to say shows nothing in the slot`() {
        // No tags AND no caution. This is the only shape that renders nothing.
        listOf("douglas-fir", "western-sword-fern").forEach { id ->
            assertNull("$id uses section", detailState(id).uses)
        }
    }

    @Test
    fun `a plant with no uses but a caution still shows the caution`() {
        // The safety regression this test exists for: Western Wild Ginger carries a warning
        // about a carcinogen and no use tag at all, because `keptUsesNote` keeps the caution
        // when the uses go. Gating the section on `uses.isNotEmpty()` swallowed it.
        val uses = checkNotNull(detailState("western-wild-ginger").uses) {
            "a plant whose only content is a caution must still render the section"
        }

        assertTrue(uses.uses.isEmpty())
        val (body, caution) = UsesNote.cautionSplit(uses.usesNote)
        assertEquals("", body)
        assertTrue(caution!!.contains("aristolochic acid"))
        // Nothing sourced to show beside it, and the disclaimer stands alone.
        assertNull(dukesLine(uses.medicinalRecordCount, uses.medicinalActivities))
        assertEquals(USES_DISCLAIMER, usesDisclaimer(uses.usesAttribution))
    }

    @Test
    fun `a caution reaches the screen for every plant whose note carries one`() {
        // The invariant, not the two instances: no plant may have a caution in its data and
        // no uses section on its screen.
        TwoKingdomFixture.summaries().filter { it.kingdom == Kingdom.PLANT }.forEach { plant ->
            val state = detailState(plant.id)
            val hasCaution =
                UsesNote.cautionSplit(TwoKingdomFixture.detail(plant.id).usesNote).second != null
            if (hasCaution) {
                assertNotNull(
                    "${plant.id} has a caution in its data and must show it",
                    state.uses,
                )
            }
        }
    }

    @Test
    fun `a plant with uses carries the caution split apart from the note`() {
        val uses = detailState("blue-elderberry").uses!!

        assertEquals(setOf(PlantUse.EDIBLE, PlantUse.MEDICINAL), uses.uses)
        val (body, caution) = UsesNote.cautionSplit(uses.usesNote)
        assertTrue(body.startsWith("Berries, late summer"))
        assertFalse("the caution must not stay in the body", body.contains("Caution:"))
        assertTrue(caution!!.startsWith("Caution: raw berries"))
        // Duke's `Poison` rows are excluded from the count.
        assertEquals(58, uses.medicinalRecordCount)
    }

    @Test
    fun `a medicinal-only plant has a source line and no curated note`() {
        val uses = detailState("common-yarrow").uses!!

        assertEquals(setOf(PlantUse.MEDICINAL), uses.uses)
        assertNull(uses.usesNote)
        assertEquals(
            "Duke's records 105 traditional uses: astringent, diuretic, wound, antiseptic",
            dukesLine(uses.medicinalRecordCount, uses.medicinalActivities),
        )
    }

    // ---- the uses section's own rules (M24, M30) -------------------------------------

    @Test
    fun `the Duke's line is absent when Duke's has no record`() {
        assertNull(dukesLine(0, emptyList()))
        assertNull(dukesLine(0, listOf("Astringent")))
        assertEquals("Duke's records 1 traditional use", dukesLine(1, emptyList()))
    }

    @Test
    fun `the disclaimer is on every uses section and credits the source when there is one`() {
        assertEquals(USES_DISCLAIMER, usesDisclaimer(null))
        assertEquals(USES_DISCLAIMER, usesDisclaimer(" "))

        val credited = usesDisclaimer("Dr. Duke's Databases · USDA ARS · CC0")
        assertTrue(credited.startsWith(USES_DISCLAIMER))
        assertTrue(credited.endsWith("Medicinal: Dr. Duke's Databases · USDA ARS · CC0."))
        // M30 and D2: no safety claim, no identification claim, anywhere in the wording.
        assertFalse(credited.contains("safe", ignoreCase = true) &&
            !credited.contains("not safety advice"))
    }

    // ---- Stats (M15, M26) ------------------------------------------------------------

    @Test
    fun `stats groups class bars under the right kingdom`() {
        val state = buildStatsUiState(TwoKingdomFixture.progress(all), all)

        assertTrue(state.showPlants)
        assertTrue(state.animalClasses.all { it.taxClass.kingdom == Kingdom.ANIMAL })
        assertTrue(state.plantClasses.all { it.taxClass.kingdom == Kingdom.PLANT })
        assertEquals(
            listOf("Trees", "Shrubs", "Herbs", "Ferns"),
            state.plantClasses.map { it.label },
        )
        assertEquals(
            state.classes.size,
            state.animalClasses.size + state.plantClasses.size,
        )
    }

    @Test
    fun `stats counts the two kingdoms separately and never blends them`() {
        val state = buildStatsUiState(TwoKingdomFixture.progress(all), all)

        assertEquals(10, state.overall.total)
        assertEquals(1, state.overall.caught)
        assertEquals(8, state.plants.total)
        assertEquals(1, state.plants.caught)

        val rainforest = state.ecosystems.single { it.ecosystem.id == "coastal-rainforest" }
        assertEquals(3, rainforest.animals.total)
        assertEquals(6, rainforest.plants.total)
    }

    @Test
    fun `every animal number is what it was before plants existed`() {
        val animalsOnly = all.filter { it.kingdom == Kingdom.ANIMAL }
        val withPlants = TwoKingdomFixture.progress(all)
        val without = TwoKingdomFixture.progress(animalsOnly)

        assertEquals(without.animals, withPlants.animals)
        assertEquals(
            without.perClass.filter { it.first.kingdom == Kingdom.ANIMAL },
            withPlants.perClass.filter { it.first.kingdom == Kingdom.ANIMAL },
        )
        assertEquals(
            without.perEcosystem.map { it.ecosystem.id to it.animals },
            withPlants.perEcosystem.map { it.ecosystem.id to it.animals },
        )
    }

    @Test
    fun `a region with no plants renders the screen exactly as it shipped`() {
        val animalsOnly = all.filter { it.kingdom == Kingdom.ANIMAL }
        val state = buildStatsUiState(TwoKingdomFixture.progress(animalsOnly), animalsOnly)

        assertFalse(state.showPlants)
        assertFalse(state.showPlantPill)
        assertTrue(state.plantClasses.isEmpty())
        assertTrue(dev.tlong.biodex.ui.stats.summaryLine(state).startsWith("10% caught"))
    }

    // ---- the reveal (S10) ------------------------------------------------------------

    @Test
    fun `the reveal counter names the kingdom it incremented`() {
        fun content(kingdom: Kingdom, caught: Int, total: Int) = RevealContent(
            commonName = "x",
            displayNumber = "P047",
            scientificName = null,
            taxClass = TaxClass.SHRUB,
            kingdom = kingdom,
            silhouetteRes = "sil_shrub",
            thumbnailModel = null,
            caughtCount = caught,
            totalCount = total,
            whereAndWhen = null,
        )
        assertEquals("4 / 80 plants", revealCounterLabel(content(Kingdom.PLANT, 4, 80)))
        assertEquals("47 / 120 animals", revealCounterLabel(content(Kingdom.ANIMAL, 47, 120)))
    }

    @Test
    fun `the detail state counts the species' own kingdom`() {
        val plant = detailState("blue-elderberry")
        assertEquals(8, plant.totalCount)
        assertEquals(1, plant.caughtCount)

        val animal = detailState("steller-jay")
        assertEquals(10, animal.totalCount)
    }
}
