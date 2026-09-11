package dev.tlong.biodex.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hero's state machine — the part of this slice that decides what the user actually sees
 * in the frame that dominates the detail screen (M04/M05, D3, S02).
 */
class HeroStateTest {

    private val url = "https://upload.wikimedia.org/owl.jpg"

    private fun hero(
        imageUrl: String? = url,
        caught: Boolean = true,
        phase: ImageLoadPhase = ImageLoadPhase.LOADED,
        online: Boolean = true,
        ownPhotoModel: String? = null,
    ) = heroVisual(imageUrl, caught, phase, online, ownPhotoModel)

    @Test
    fun `an uncaught species shows the reference picture dimmed, as its grid tile does (D52)`() {
        assertEquals(
            HeroVisual.DimmedReference(url),
            hero(caught = false, phase = ImageLoadPhase.LOADED),
        )
    }

    @Test
    fun `an uncaught species with no picture to draw is still withheld, and says nothing about why`() {
        // Every route that leaves an uncaught hero without a picture reports NOT_CAUGHT
        // rather than the load failure behind it: the species is withheld either way, and a
        // "could not be loaded" line about a picture the user has not earned is noise.
        for (online in listOf(true, false)) {
            assertEquals(
                HeroVisual.Silhouette(SilhouetteReason.NOT_CAUGHT),
                hero(caught = false, phase = ImageLoadPhase.FAILED, online = online),
            )
            assertEquals(
                HeroVisual.Silhouette(SilhouetteReason.NOT_CAUGHT),
                hero(imageUrl = null, caught = false, online = online),
            )
        }
    }

    @Test
    fun `an uncaught hero carries no note, dimmed picture or silhouette`() {
        assertNull(heroNote(hero(caught = false, phase = ImageLoadPhase.LOADED)))
        assertNull(heroNote(hero(caught = false, phase = ImageLoadPhase.FAILED, online = false)))
    }

    @Test
    fun `a caught species with a loaded image shows the reference photo (M04)`() {
        assertEquals(HeroVisual.Reference(url), hero())
    }

    @Test
    fun `a caught species that prefers its own photo leads with it, whatever the reference is doing (M46)`() {
        for (phase in ImageLoadPhase.entries) {
            assertEquals(HeroVisual.OwnPhoto("thumbnails/a.jpg"), hero(phase = phase, ownPhotoModel = "thumbnails/a.jpg"))
        }
        assertEquals(HeroVisual.OwnPhoto("thumbnails/a.jpg"), hero(imageUrl = null, ownPhotoModel = "thumbnails/a.jpg"))
    }

    @Test
    fun `an uncaught species never leads with a stale thumbnail (M05 over M46)`() {
        // Only a caught species can have a photograph of its own. A thumbnail left behind by
        // an entry that was deleted must not unlock the hero; the dimmed reference is what
        // an uncaught species gets, exactly as if there were no thumbnail at all.
        assertEquals(
            HeroVisual.DimmedReference(url),
            hero(caught = false, ownPhotoModel = "thumbnails/stale.jpg"),
        )
    }

    @Test
    fun `a caught species with no image url falls back to the silhouette, not to an error`() {
        assertEquals(
            HeroVisual.Silhouette(SilhouetteReason.NO_IMAGE),
            hero(imageUrl = null, phase = ImageLoadPhase.FAILED),
        )
    }

    @Test
    fun `while loading, the hero is the image slot - the silhouette is only underneath it`() {
        assertEquals(HeroVisual.LoadingReference(url), hero(phase = ImageLoadPhase.LOADING))
    }

    @Test
    fun `offline with nothing cached degrades gracefully rather than erroring (D3, S02)`() {
        assertEquals(
            HeroVisual.Silhouette(SilhouetteReason.OFFLINE),
            hero(phase = ImageLoadPhase.FAILED, online = false),
        )
    }

    @Test
    fun `offline but cached is indistinguishable from online - Coil answers from disk (S02)`() {
        assertEquals(HeroVisual.Reference(url), hero(online = false))
    }

    @Test
    fun `online failure is a real failure and says so`() {
        assertEquals(
            HeroVisual.Silhouette(SilhouetteReason.LOAD_FAILED),
            hero(phase = ImageLoadPhase.FAILED),
        )
    }

    @Test
    fun `only the two failure states earn a message under the hero`() {
        assertNull(heroNote(hero()))
        assertNull(heroNote(hero(caught = false)))
        assertNull(heroNote(hero(imageUrl = null)))
        assertNotNull(heroNote(hero(phase = ImageLoadPhase.FAILED)))
        assertNotNull(heroNote(hero(phase = ImageLoadPhase.FAILED, online = false)))
    }
}
