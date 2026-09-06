package dev.tlong.biodex.ui.detail

import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.SpeciesDetail
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

class EntryDetailStateTest {

    private val ecosystems = listOf(
        Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
        Ecosystem("oak-chaparral", "pacific", "Oak Woodland & Chaparral", 3),
        Ecosystem("riparian-wetland", "pacific", "Riparian & Wetland", 4),
    )

    private fun detail(
        ecosystemIds: List<String>,
        caught: Boolean = false,
    ) = SpeciesDetail(
        summary = SpeciesSummary(
            id = "western-screech-owl",
            regionId = "pacific",
            dexNumber = 21,
            source = SpeciesSource.CURATED,
            detailsPending = false,
            commonName = "Western Screech-Owl",
            scientificName = "Megascops kennicottii",
            taxClass = TaxClass.BIRD,
            kingdom = Kingdom.ANIMAL,
            silhouetteRes = "sil_bird",
            ecosystemIds = ecosystemIds,
            caughtAt = if (caught) 1L else null,
            thumbPath = null,
            captureCount = 0,
        ),
        habitatText = "Low-elevation woodlands.",
        description = null,
        imageUrl = null,
        infoUrl = null,
        imageAttribution = null,
        userEditedFields = emptyList(),
    )

    private fun state(
        species: SpeciesDetail?,
        captures: List<dev.tlong.biodex.domain.Capture> = emptyList(),
        progress: dev.tlong.biodex.domain.DexProgress =
            dev.tlong.biodex.domain.DexProgress.Empty,
        online: Boolean = true,
        rangeGrid: dev.tlong.biodex.domain.RangeGrid = dev.tlong.biodex.domain.RangeGrid.Empty,
    ) = runBlocking {
        entryDetailUiState(
            detail = MutableStateFlow(species),
            ecosystems = MutableStateFlow(ecosystems),
            captures = MutableStateFlow(captures),
            progress = MutableStateFlow(progress),
            online = MutableStateFlow(online),
            rangeGrid = MutableStateFlow(rangeGrid),
        ).first()
    }

    @Test
    fun `the photo strip is the capture list, and it is empty until something is caught`() {
        assertEquals(emptyList<Any>(), state(detail(listOf("oak-chaparral"))).captures)

        val capture = dev.tlong.biodex.domain.Capture(
            id = "cap-1",
            speciesId = "western-screech-owl",
            photoUri = "content://media/1",
            thumbPath = "thumbnails/cap-1.jpg",
            takenAt = 1L,
            createdAt = 1L,
        )
        val s = state(detail(listOf("oak-chaparral")), captures = listOf(capture))
        assertEquals(listOf("cap-1"), s.captures.map { it.id })
    }

    @Test
    fun `the reveal reads its counter off dex progress, not off the species row`() {
        val s = state(
            detail(listOf("oak-chaparral")),
            progress = dev.tlong.biodex.domain.DexProgress(
                regionId = "pacific",
                regionName = "Pacific USA",
                animals = dev.tlong.biodex.domain.Meter(caught = 1, total = 120),
                plants = dev.tlong.biodex.domain.Meter(0, 0),
                perClass = emptyList(),
                perEcosystem = emptyList(),
            ),
        )
        assertEquals(1, s.caughtCount)
        assertEquals(120, s.totalCount)
    }

    @Test
    fun `a plant's reveal counts the plant meter, not both lists added together`() {
        val elder = detail(listOf("riparian-wetland")).let {
            it.copy(
                summary = it.summary.copy(
                    id = "blue-elderberry",
                    kingdom = Kingdom.PLANT,
                    taxClass = TaxClass.SHRUB,
                ),
            )
        }
        val s = state(
            elder,
            progress = dev.tlong.biodex.domain.DexProgress(
                regionId = "pacific",
                regionName = "Pacific USA",
                animals = dev.tlong.biodex.domain.Meter(caught = 47, total = 120),
                plants = dev.tlong.biodex.domain.Meter(caught = 4, total = 80),
                perClass = emptyList(),
                perEcosystem = emptyList(),
            ),
        )

        // S10: "4 / 80 plants". 51 / 200 would be a number the user never sees anywhere else.
        assertEquals(4, s.caughtCount)
        assertEquals(80, s.totalCount)
    }

    @Test
    fun `ecosystem ids resolve to names in catalogue sort order`() {
        val s = state(detail(listOf("riparian-wetland", "oak-chaparral")))
        assertEquals(listOf("Oak Woodland & Chaparral", "Riparian & Wetland"), s.ecosystemNames)
    }

    @Test
    fun `an unknown ecosystem id is dropped rather than rendered raw`() {
        val s = state(detail(listOf("oak-chaparral", "not-a-real-ecosystem")))
        assertEquals(listOf("Oak Woodland & Chaparral"), s.ecosystemNames)
    }

    @Test
    fun `a species id with no row reads as missing, not as loading forever`() {
        val s = state(null)
        assertNull(s.detail)
        assertTrue(s.missing)
    }

    @Test
    fun `connectivity reaches the state - it is what the hero uses to explain a miss`() {
        assertTrue(state(detail(listOf("oak-chaparral"))).online)
        assertFalse(state(detail(listOf("oak-chaparral")), online = false).online)
    }

    @Test
    fun `caught date formats only when the species is caught`() {
        assertEquals("", formatCaughtDate(null))
        assertTrue(formatCaughtDate(1_756_512_000_000L).isNotEmpty())
    }
    // -----------------------------------------------------------------------
    // Whose caution reaches a screen.
    // -----------------------------------------------------------------------

    private fun withCaution(kingdom: Kingdom, taxClass: TaxClass) =
        detail(listOf("coastal-rainforest")).let {
            it.copy(
                summary = it.summary.copy(kingdom = kingdom, taxClass = taxClass),
                usesNote = "Caution: deadly. Half a cap can kill an adult.",
            )
        }

    @Test
    fun `a fungus caution reaches the screen`() {
        // The regression this exists for: the uses slot was gated on PLANT, so every fungal
        // caution ever written was invisible — thirty of them, enforced by a build rule that
        // could not tell, and never once drawn. The kingdom that carries a warning is not the
        // kingdom that carries uses, and this pins the difference.
        val uses = state(withCaution(Kingdom.FUNGUS, TaxClass.MUSHROOM)).uses
        assertTrue("a fungus with a caution must show it", uses != null)
        assertTrue(uses!!.uses.isEmpty())
    }

    @Test
    fun `an animal still shows no uses section, caution or not`() {
        assertNull(state(withCaution(Kingdom.ANIMAL, TaxClass.BIRD)).uses)
    }

    // -----------------------------------------------------------------------
    // D34's range map, and when it must not be drawn.
    // -----------------------------------------------------------------------

    /** A 4x2 world with the left half land, as the asset's base64 bitmask. */
    private fun tinyGrid(): dev.tlong.biodex.domain.RangeGrid {
        // Cells 0,1 and 4,5 set: bits 0,1,4,5 of a single byte -> 0b00110011 = 0x33.
        val mask = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x33))
        return dev.tlong.biodex.domain.RangeGrid.fromMask(mask, width = 4, height = 2)
    }

    @Test
    fun `the outline decodes from the asset's bitmask, bit per cell, row-major`() {
        val grid = tinyGrid()
        assertTrue(grid.isUsable)
        assertEquals(listOf(true, true, false, false, true, true, false, false), grid.land)
    }

    @Test
    fun `a malformed outline decodes to nothing rather than throwing`() {
        // A map is an ornament; it must never take down the screen holding the user's photos.
        val short = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x01))
        assertEquals(
            dev.tlong.biodex.domain.RangeGrid.Empty,
            dev.tlong.biodex.domain.RangeGrid.fromMask(short, width = 64, height = 64),
        )
        assertEquals(
            dev.tlong.biodex.domain.RangeGrid.Empty,
            dev.tlong.biodex.domain.RangeGrid.fromMask("not base64 at all!!", 4, 2),
        )
        assertEquals(
            dev.tlong.biodex.domain.RangeGrid.Empty,
            dev.tlong.biodex.domain.RangeGrid.fromMask(null, 4, 2),
        )
    }

    @Test
    fun `the map is drawn when the species has cells and the outline is loaded`() {
        val species = detail(listOf("coastal-rainforest")).copy(rangeCells = listOf(1, 5))
        val map = state(species, rangeGrid = tinyGrid()).rangeMap
        assertTrue(map != null)
        assertEquals(setOf(1, 5), map!!.cells)
    }

    @Test
    fun `no cells means no map, not an empty one`() {
        // A species GBIF holds no records for — every user-added one, until a backfill. An
        // empty frame would claim the species lives nowhere.
        val species = detail(listOf("coastal-rainforest")).copy(rangeCells = emptyList())
        assertNull(state(species, rangeGrid = tinyGrid()).rangeMap)
    }

    @Test
    fun `no outline means no map, however many cells the species has`() {
        // An install that has migrated but not yet re-imported the catalogue.
        val species = detail(listOf("coastal-rainforest")).copy(rangeCells = listOf(1, 5))
        assertNull(state(species).rangeMap)
    }

}
