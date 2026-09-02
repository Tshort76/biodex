package dev.tlong.biodex.data.photo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * ARCHITECTURE.md 4.2's three states. The invariant under test is that the *kind* of failure
 * decides the UX: a revoked grant is permanent and offers a re-link, everything else is
 * transient and does not. Getting this backwards would either nag the user to re-link a photo
 * that is merely offline, or silently strand one that is gone for good.
 */
class PhotoRefTest {

    @Test
    fun `security exception is the permanent revoked state`() {
        assertEquals(PhotoRef.Revoked, classifyResolveFailure(SecurityException("gone")))
    }

    @Test
    fun `missing file is transient, not revoked`() {
        assertEquals(PhotoRef.Unavailable, classifyResolveFailure(FileNotFoundException("cloud")))
        assertEquals(PhotoRef.Unavailable, classifyResolveFailure(IOException("offline")))
    }

    @Test
    fun `an unexpected failure degrades to transient rather than crashing`() {
        assertEquals(PhotoRef.Unavailable, classifyResolveFailure(IllegalStateException("?")))
    }

    @Test
    fun `a successful probe is Available and carries the uri`() {
        val ref = resolvePhotoRef("content://media/1", localCopyPath = null) { null }
        assertEquals(PhotoRef.Available("content://media/1"), ref)
    }

    @Test
    fun `a local copy short-circuits resolution — the gallery is never touched`() {
        var probed = false
        val ref = resolvePhotoRef("content://media/1", "photos/abc.jpg") {
            probed = true
            SecurityException("would have been revoked")
        }
        assertEquals(PhotoRef.LocalCopy("photos/abc.jpg"), ref)
        assertEquals(false, probed)
    }

    @Test
    fun `only Available and LocalCopy can render a full-size photo`() {
        assertEquals(true, PhotoRef.Available("u").isFullSizeShowable)
        assertEquals(true, PhotoRef.LocalCopy("p").isFullSizeShowable)
        assertEquals(false, PhotoRef.Revoked.isFullSizeShowable)
        assertEquals(false, PhotoRef.Unavailable.isFullSizeShowable)
    }
}
