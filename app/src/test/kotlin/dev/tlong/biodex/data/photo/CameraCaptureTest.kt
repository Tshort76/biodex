package dev.tlong.biodex.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D26's rules. The camera itself cannot be tested without a phone; what *can* be pinned is the
 * decision that makes capture-to-cache worth choosing at all — that a camera shot's cache file
 * is always swept afterwards, and only a cache file is ever promoted.
 */
class CameraCaptureTest {

    @Test
    fun `a camera shot is promoted into the gallery, whatever the kingdom`() {
        // This is the property that decided capture-to-cache over capture-to-gallery: the
        // file has no home until registration decides it. (Until D59 a plant's was never
        // promoted at all; every kingdom keeps its photograph now.)
        assertTrue(shouldPromoteToGallery(PhotoSourceKind.CAMERA_CACHE))
    }

    @Test
    fun `a photo the user picked from the gallery is never promoted`() {
        // It is already where it belongs; inserting a second copy would duplicate it.
        assertFalse(shouldPromoteToGallery(PhotoSourceKind.GALLERY_PICKER))
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
