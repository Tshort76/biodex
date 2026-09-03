package dev.tlong.biodex.ui.common

import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §5.3.1's three states, and the one rule it asks explicitly for a test to pin. */
class TileStateTest {

    private fun species(
        caught: Boolean,
        thumbPath: String?,
        kingdom: Kingdom = Kingdom.PLANT,
        imageUrl: String? = "https://upload.wikimedia.org/oregon-grape.jpg",
    ) = SpeciesSummary(
        id = "p048",
        regionId = "pacific",
        dexNumber = 2048,
        source = SpeciesSource.CURATED,
        detailsPending = false,
        commonName = "Oregon Grape",
        scientificName = "Mahonia aquifolium",
        taxClass = if (kingdom == Kingdom.PLANT) TaxClass.SHRUB else TaxClass.BIRD,
        kingdom = kingdom,
        silhouetteRes = "sil_shrub",
        ecosystemIds = emptyList(),
        caughtAt = if (caught) 1L else null,
        thumbPath = thumbPath,
        imageUrl = imageUrl,
        captureCount = if (caught) 1 else 0,
    )

    @Test
    fun `an uncaught species is the silhouette on the neutral ground, as before`() {
        val state = tileStateFor(species(caught = false, thumbPath = null))

        assertEquals(TileState.UNCAUGHT, state)
        assertFalse(tileWearsAccentChrome(state))
        assertNull(tileGlyph(state))
    }

    @Test
    fun `a catch with the user's own photo is unchanged`() {
        // Animals, fungi, and every plant registered before M41.
        val animal = tileStateFor(
            species(caught = true, thumbPath = "thumbnails/a.jpg", kingdom = Kingdom.ANIMAL),
        )
        val oldPlant = tileStateFor(species(caught = true, thumbPath = "thumbnails/p.jpg"))

        assertEquals(TileState.CAUGHT_OWN_PHOTO, animal)
        assertEquals(TileState.CAUGHT_OWN_PHOTO, oldPlant)
        assertFalse("no new chrome on a tile that already worked", tileWearsAccentChrome(animal))
        assertNull(tileGlyph(oldPlant))
    }

    @Test
    fun `a catch with no photo of the user's own gets the reference image and the accent chrome`() {
        val state = tileStateFor(species(caught = true, thumbPath = null))

        assertEquals(TileState.CAUGHT_REFERENCE_IMAGE, state)
        assertTrue(tileWearsAccentChrome(state))
        assertNotNull(tileGlyph(state))
    }

    // -----------------------------------------------------------------------
    // The rule §5.3.1 names as the one to pin.
    // -----------------------------------------------------------------------

    @Test
    fun `the accent chrome does not depend on the reference image being fetchable`() {
        // The chrome is what says *caught*. If it needed the network, a caught plant would
        // read as a still-missing one whenever the phone was offline and the picture had not
        // cached — which is the exact confusion the colour exists to prevent. So the tile
        // state is computed from the catch alone, and there is deliberately no parameter here
        // for whether the image loaded.
        val noImageAtAll = species(caught = true, thumbPath = null, imageUrl = null)

        val state = tileStateFor(noImageAtAll)

        assertEquals(TileState.CAUGHT_REFERENCE_IMAGE, state)
        assertTrue("still caught, still accented", tileWearsAccentChrome(state))
        assertNotNull("still marked", tileGlyph(state))
        // And it is still plainly not an uncaught tile, which is the separation that matters.
        assertFalse(tileWearsAccentChrome(TileState.UNCAUGHT))
    }

    @Test
    fun `the mark says nothing about how the species was named`() {
        // Path-neutral (§5.3): a plant typed in by name and one identified through Pl@ntNet
        // get the same tile, because the capture row records no provenance (Q06).
        assertFalse(NO_OWN_PHOTO_MARK.contains("Pl@ntNet"))
        assertEquals("caught — no photo of your own", NO_OWN_PHOTO_MARK)
    }
}
