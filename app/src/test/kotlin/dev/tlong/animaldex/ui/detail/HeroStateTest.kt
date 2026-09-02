package dev.tlong.animaldex.ui.detail

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
    ) = heroVisual(imageUrl, caught, phase, online)

    @Test
    fun `an uncaught species is withheld - silhouette, whatever the network is doing (M05)`() {
        for (phase in ImageLoadPhase.entries) {
            for (online in listOf(true, false)) {
                assertEquals(
                    HeroVisual.Silhouette(SilhouetteReason.NOT_CAUGHT),
                    hero(caught = false, phase = phase, online = online),
                )
            }
        }
    }

    @Test
    fun `a caught species with a loaded image shows the reference photo (M04)`() {
        assertEquals(HeroVisual.Reference(url), hero())
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
