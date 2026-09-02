package dev.tlong.biodex.ui.photoviewer

import dev.tlong.biodex.data.photo.PhotoRef
import dev.tlong.biodex.domain.Capture
import dev.tlong.biodex.domain.Entry
import dev.tlong.biodex.domain.SpeciesDetail
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
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
 * M12, as the user meets it. Whatever the reference's state, the stored thumbnail is still
 * there and the entry is still caught — the only thing that changes is what the screen says
 * and whether it offers a re-link.
 */
class PhotoViewerStateTest {

    private val capture = Capture(
        id = "cap-1",
        speciesId = "western-screech-owl",
        photoUri = "content://media/1",
        thumbPath = "thumbnails/cap-1.jpg",
        takenAt = 1L,
        createdAt = 1L,
    )

    private val detail = SpeciesDetail(
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
            ecosystemIds = emptyList(),
            caughtAt = 1L,
            thumbPath = "thumbnails/cap-1.jpg",
            captureCount = 1,
        ),
        habitatText = null,
        description = null,
        imageUrl = null,
        callUrl = null,
        infoUrl = null,
        imageAttribution = null,
        callAttribution = null,
        userEditedFields = emptyList(),
    )

    private fun state(
        ref: PhotoRef?,
        entry: Entry? = Entry("western-screech-owl", 1L, "cap-1", captureCount = 1),
    ) = runBlocking {
        photoViewerUiState(
            capture = MutableStateFlow(capture),
            speciesDetail = MutableStateFlow(detail),
            entry = MutableStateFlow(entry),
            ref = MutableStateFlow(ref),
        ).first()
    }

    @Test
    fun `a revoked reference offers a re-link and keeps the thumbnail`() {
        val s = state(PhotoRef.Revoked)
        assertTrue(s.availability.offerRelink)
        assertFalse(s.availability.showFullSize)
        assertNotNull(s.availability.bannerText)
        assertEquals("thumbnails/cap-1.jpg", s.thumbPath)
    }

    @Test
    fun `a cloud-only reference explains itself but does not offer a re-link`() {
        val s = state(PhotoRef.Unavailable)
        assertFalse("this one is transient — re-linking would be wrong", s.availability.offerRelink)
        assertFalse(s.availability.showFullSize)
        assertNotNull(s.availability.bannerText)
        assertEquals("thumbnails/cap-1.jpg", s.thumbPath)
    }

    @Test
    fun `a working reference says nothing at all`() {
        val s = state(PhotoRef.Available("content://media/1"))
        assertNull(s.availability.bannerText)
        assertTrue(s.availability.showFullSize)
    }

    @Test
    fun `a local copy renders full size without touching the gallery`() {
        val s = state(PhotoRef.LocalCopy("photos/cap-1.jpg"))
        assertTrue(s.availability.showFullSize)
        assertNull(s.availability.bannerText)
    }

    @Test
    fun `before the probe returns, nothing is claimed either way`() {
        val s = state(ref = null)
        assertNull(s.availability.bannerText)
        assertFalse(s.availability.showFullSize)
    }

    @Test
    fun `the favorite flag follows the entry, not the capture order`() {
        assertTrue(state(PhotoRef.Revoked).isFavorite)
        assertFalse(
            state(
                PhotoRef.Revoked,
                entry = Entry("western-screech-owl", 1L, "cap-9", captureCount = 2),
            ).isFavorite,
        )
    }

    @Test
    fun `deleting the only capture is flagged so the warning can say so (S07)`() {
        assertTrue(state(PhotoRef.Available("u")).isLastCapture)
        assertFalse(
            state(
                PhotoRef.Available("u"),
                entry = Entry("western-screech-owl", 1L, "cap-1", captureCount = 3),
            ).isLastCapture,
        )
    }
}
