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

/** §5.3.1's three states, the one rule it asks explicitly for a test to pin, and D39's picture order. */
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
    fun `an uncaught species keeps the neutral ground and no mark`() {
        val state = tileStateFor(species(caught = false, thumbPath = null))

        assertEquals(TileState.UNCAUGHT, state)
        assertFalse(tileWearsAccentChrome(state))
        assertNull(tileGlyph(state))
    }

    @Test
    fun `a catch with the user's own photo keeps its neutral chrome`() {
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
    fun `a catch with no photo of the user's own gets the accent chrome and the leaf`() {
        val state = tileStateFor(species(caught = true, thumbPath = null))

        assertEquals(TileState.CAUGHT_NO_OWN_PHOTO, state)
        assertTrue(tileWearsAccentChrome(state))
        assertNotNull(tileGlyph(state))
    }

    // -----------------------------------------------------------------------
    // D39: which picture a caught cell draws, and what it falls back to.
    // -----------------------------------------------------------------------

    @Test
    fun `a caught species leads with its reference picture and keeps its own thumbnail as the fallback`() {
        val sources = tileImageSources(
            species(caught = true, thumbPath = "thumbnails/a.jpg", kingdom = Kingdom.ANIMAL),
        )

        assertEquals(
            listOf(
                TileImage.Reference("https://upload.wikimedia.org/oregon-grape.jpg"),
                TileImage.OwnThumbnail("thumbnails/a.jpg"),
            ),
            sources,
        )
    }

    @Test
    fun `a catch with no reference picture still draws its own thumbnail`() {
        // A user-added species whose lookup found no image, or a catalogue entry with none:
        // the thumbnail is the only picture there is, and the cell must not skip to the shape.
        val sources = tileImageSources(
            species(caught = true, thumbPath = "thumbnails/a.jpg", imageUrl = null),
        )

        assertEquals(listOf(TileImage.OwnThumbnail("thumbnails/a.jpg")), sources)
    }

    @Test
    fun `a photoless catch has only the reference picture to try`() {
        assertEquals(
            listOf(TileImage.Reference("https://upload.wikimedia.org/oregon-grape.jpg")),
            tileImageSources(species(caught = true, thumbPath = null)),
        )
        assertEquals(emptyList<TileImage>(), tileImageSources(species(caught = true, thumbPath = null, imageUrl = null)))
    }

    @Test
    fun `an uncaught species draws its reference picture and nothing else`() {
        // D41. A thumbnail on an uncaught row would be a stale capture; it is never drawn.
        assertEquals(
            listOf(TileImage.Reference("https://upload.wikimedia.org/oregon-grape.jpg")),
            tileImageSources(species(caught = false, thumbPath = "thumbnails/stale.jpg")),
        )
    }

    @Test
    fun `only an uncaught tile is dimmed`() {
        // D41: the dimming is the whole caught/uncaught distinction now that both draw the
        // same picture, so it is a function of the state alone and never of the load.
        assertTrue(tileDrawsDimmed(TileState.UNCAUGHT))
        assertFalse(tileDrawsDimmed(TileState.CAUGHT_OWN_PHOTO))
        assertFalse(tileDrawsDimmed(TileState.CAUGHT_NO_OWN_PHOTO))
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

        assertEquals(TileState.CAUGHT_NO_OWN_PHOTO, state)
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

    @Test
    fun `the loud tick marks a caught species whose cell is showing a shape`() {
        // The three ways a caught cell ends up drawing a silhouette — a broken or deleted
        // gallery photo, a plant that never had one (M41), a reference image not yet cached —
        // all read the same on the grid, which is the honest thing it can say (M44).
        assertTrue(tileWearsLoudTick(TileState.CAUGHT_OWN_PHOTO, showingSilhouette = true))
        assertTrue(tileWearsLoudTick(TileState.CAUGHT_NO_OWN_PHOTO, showingSilhouette = true))
    }

    @Test
    fun `a caught cell showing a picture keeps the quiet tick`() {
        assertFalse(tileWearsLoudTick(TileState.CAUGHT_OWN_PHOTO, showingSilhouette = false))
        assertFalse(tileWearsLoudTick(TileState.CAUGHT_NO_OWN_PHOTO, showingSilhouette = false))
    }

    @Test
    fun `an uncaught cell never wears a tick, drawing a shape or not`() {
        // The one that would be a real bug: an uncaught species is *always* a silhouette, so a
        // rule keyed on the silhouette alone would put a caught mark on every missing species.
        assertFalse(tileWearsLoudTick(TileState.UNCAUGHT, showingSilhouette = true))
        assertFalse(tileWearsLoudTick(TileState.UNCAUGHT, showingSilhouette = false))
    }

    @Test
    fun `the chrome still refuses to know whether the image loaded`() {
        // M44 adds a rule that depends on the load; §5.3.1's does not, and must not. Keeping
        // both in this file is what makes the difference visible to whoever changes one.
        assertTrue(tileWearsAccentChrome(TileState.CAUGHT_NO_OWN_PHOTO))
        assertFalse(tileWearsAccentChrome(TileState.UNCAUGHT))
    }
}
