package dev.tlong.animaldex.ui.detail

import dev.tlong.animaldex.domain.Ecosystem
import dev.tlong.animaldex.domain.SpeciesDetail
import dev.tlong.animaldex.domain.SpeciesSource
import dev.tlong.animaldex.domain.SpeciesSummary
import dev.tlong.animaldex.domain.TaxClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryDetailStateTest {

    private val ecosystems = listOf(
        Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
        Ecosystem("oak-chaparral", "pacific", "Oak Woodland & Chaparral", 3),
        Ecosystem("riparian-wetland", "pacific", "Riparian & Wetland", 4),
    )

    private fun detail(ecosystemIds: List<String>) = SpeciesDetail(
        summary = SpeciesSummary(
            id = "western-screech-owl",
            regionId = "pacific",
            dexNumber = 21,
            source = SpeciesSource.CURATED,
            detailsPending = false,
            commonName = "Western Screech-Owl",
            scientificName = "Megascops kennicottii",
            taxClass = TaxClass.BIRD,
            silhouetteRes = "sil_bird",
            ecosystemIds = ecosystemIds,
            caughtAt = null,
            thumbPath = null,
            captureCount = 0,
        ),
        habitatText = "Low-elevation woodlands.",
        description = null,
        imageUrl = null,
        callUrl = null,
        infoUrl = null,
        imageAttribution = null,
        callAttribution = null,
        userEditedFields = emptyList(),
    )

    private fun state(species: SpeciesDetail?) = runBlocking {
        entryDetailUiState(
            detail = MutableStateFlow(species),
            ecosystems = MutableStateFlow(ecosystems),
        ).first()
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
    fun `caught date formats only when the species is caught`() {
        assertEquals("", formatCaughtDate(null))
        assertTrue(formatCaughtDate(1_756_512_000_000L).isNotEmpty())
    }
}
