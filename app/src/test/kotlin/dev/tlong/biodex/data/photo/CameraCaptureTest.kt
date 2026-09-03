package dev.tlong.biodex.data.photo

import dev.tlong.biodex.domain.Kingdom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D26's rules. The camera itself cannot be tested without a phone; what *can* be pinned is the
 * decision that makes capture-to-cache worth choosing at all — that a plant's photograph never
 * reaches the gallery, and that a camera shot's cache file is always swept afterwards.
 */
class CameraCaptureTest {

    @Test
    fun `a plant keeps no photograph of the user's own`() {
        assertFalse(keepsOwnPhoto(Kingdom.PLANT))
        assertTrue(keepsOwnPhoto(Kingdom.ANIMAL))
        // The user asked for pictures of their mushrooms specifically (§5.3).
        assertTrue(keepsOwnPhoto(Kingdom.FUNGUS))
    }

    @Test
    fun `a plant's camera shot is never promoted into the gallery`() {
        // This is the property that decided capture-to-cache over capture-to-gallery. Under
        // the other design the app would have to *delete* it from the gallery afterwards,
        // which on API 29+ can prompt the user for a photo they never wanted saved.
        assertFalse(shouldPromoteToGallery(PhotoSourceKind.CAMERA_CACHE, Kingdom.PLANT))
        assertTrue(shouldPromoteToGallery(PhotoSourceKind.CAMERA_CACHE, Kingdom.ANIMAL))
        assertTrue(shouldPromoteToGallery(PhotoSourceKind.CAMERA_CACHE, Kingdom.FUNGUS))
    }

    @Test
    fun `a photo the user picked from the gallery is never promoted`() {
        // It is already where it belongs; inserting a second copy would duplicate it.
        assertFalse(shouldPromoteToGallery(PhotoSourceKind.GALLERY_PICKER, Kingdom.ANIMAL))
        assertFalse(shouldPromoteToGallery(PhotoSourceKind.GALLERY_PICKER, Kingdom.PLANT))
    }

    @Test
    fun `every camera shot's cache file is swept, whatever became of it`() {
        assertTrue(shouldDeleteCacheFile(PhotoSourceKind.CAMERA_CACHE))
        // A picked photo is the user's own file in their own gallery — never this app's to
        // delete, under any kingdom (M10).
        assertFalse(shouldDeleteCacheFile(PhotoSourceKind.GALLERY_PICKER))
    }

    @Test
    fun `the cache path is the one the FileProvider declares`() {
        // `res/xml/file_paths.xml` grants exactly `cacheDir/capture/`; a path outside it would
        // make the camera intent fail at `getUriForFile` rather than at the shutter.
        assertEquals("capture/abc.jpg", cameraCacheRelativePath("abc"))
        assertTrue(cameraCacheRelativePath("abc").startsWith("$CAMERA_CACHE_DIR/"))
    }
}
