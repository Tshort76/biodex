package dev.tlong.biodex.ui.detail

import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.SpeciesDetail
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.media.CallPlayback
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
        callUrl: String? = null,
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
        callUrl = callUrl,
        infoUrl = null,
        imageAttribution = null,
        callAttribution = null,
        userEditedFields = emptyList(),
    )

    private fun state(
        species: SpeciesDetail?,
        captures: List<dev.tlong.biodex.domain.Capture> = emptyList(),
        progress: dev.tlong.biodex.domain.DexProgress =
            dev.tlong.biodex.domain.DexProgress.Empty,
        playback: CallPlayback = CallPlayback.Idle,
        online: Boolean = true,
    ) = runBlocking {
        entryDetailUiState(
            detail = MutableStateFlow(species),
            ecosystems = MutableStateFlow(ecosystems),
            captures = MutableStateFlow(captures),
            progress = MutableStateFlow(progress),
            playback = MutableStateFlow(playback),
            online = MutableStateFlow(online),
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
    fun `the call row is derived state - a null callUrl is disabled whatever is playing`() {
        val s = state(
            detail(listOf("oak-chaparral")),
            playback = CallPlayback.Playing("https://xeno-canto.org/1/download"),
        )
        assertFalse(s.callRow.enabled)
        assertFalse(s.callRow.playing)
    }

    @Test
    fun `a species whose call is the one playing reads as playing`() {
        val url = "https://xeno-canto.org/1/download"
        val s = state(
            detail(listOf("oak-chaparral"), callUrl = url),
            playback = CallPlayback.Playing(url),
        )
        assertTrue(s.callRow.enabled)
        assertTrue(s.callRow.playing)
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
}
