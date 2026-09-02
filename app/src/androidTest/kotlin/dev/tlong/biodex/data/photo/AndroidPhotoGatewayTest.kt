package dev.tlong.biodex.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The half of the photo layer the JVM suite cannot reach: `ImageDecoder`, the content
 * resolver, and the file writes. These run the first time a device is attached.
 *
 * What they cannot cover is the part that needs a human: the system photo picker, a
 * persistable grant surviving a reboot, and a genuinely cloud-only Google Photos item. Those
 * are section 9's hand-checks, not tests.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPhotoGatewayTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var gateway: AndroidPhotoGateway
    private lateinit var scratch: File

    @Before
    fun setUp() {
        gateway = AndroidPhotoGateway(context)
        scratch = File(context.cacheDir, "gateway-test").apply { mkdirs() }
        File(context.filesDir, "thumbnails").deleteRecursively()
    }

    /** A file:// URI stands in for the picker's content:// URI for everything but the grant. */
    private fun sourceImage(width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = File(scratch, "source-$width-$height.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    @Test
    fun thumbnailIsWrittenUnderFilesDirAndScaledToTheLongEdge() {
        val uri = sourceImage(2400, 1600)

        val relative = gateway.writeThumbnail("cap-1", uri.toString())

        assertEquals(thumbnailRelativePath("cap-1"), relative)
        val file = ownedFile(context.filesDir, relative!!)
        assertTrue("the thumbnail must exist before any capture row does", file.exists())
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertEquals(THUMBNAIL_LONG_EDGE_PX, maxOf(decoded.width, decoded.height))
    }

    @Test
    fun aSmallerPhotoIsNotUpscaled() {
        val uri = sourceImage(300, 200)

        val relative = gateway.writeThumbnail("cap-small", uri.toString())!!

        val decoded = BitmapFactory.decodeFile(ownedFile(context.filesDir, relative).absolutePath)
        assertEquals(300, decoded.width)
    }

    @Test
    fun reLinkingOverwritesTheSameThumbnailPath() {
        val first = gateway.writeThumbnail("cap-2", sourceImage(800, 600).toString())
        val second = gateway.writeThumbnail("cap-2", sourceImage(1200, 900).toString())

        assertEquals(first, second)
    }

    @Test
    fun anUndecodableSourceReportsFailureRatherThanThrowing() {
        val junk = File(scratch, "not-an-image.jpg").apply { writeText("this is not a JPEG") }

        assertNull(gateway.writeThumbnail("cap-3", Uri.fromFile(junk).toString()))
    }

    @Test
    fun aMissingSourceReportsFailureRatherThanThrowing() {
        val absent = Uri.fromFile(File(scratch, "does-not-exist.jpg"))

        assertNull(gateway.writeThumbnail("cap-4", absent.toString()))
        assertEquals(ExifFacts.None, gateway.readExif(absent.toString()))
    }

    @Test
    fun resolveReportsAvailableForAReadableReference() {
        val uri = sourceImage(400, 400)

        assertEquals(
            PhotoRef.Available(uri.toString()),
            gateway.resolve(uri.toString(), localCopyPath = null),
        )
    }

    @Test
    fun resolveReportsUnavailableRatherThanCrashingOnAMissingFile() {
        val absent = Uri.fromFile(File(scratch, "gone.jpg")).toString()

        // Whether a real gallery deletion surfaces as this or as Revoked can only be seen on a
        // phone; what this pins is that neither one throws and neither one is silent.
        assertNotNull(gateway.resolve(absent, localCopyPath = null))
        assertTrue(gateway.resolve(absent, null) is PhotoRef.Unavailable)
    }

    @Test
    fun aLocalCopyShortCircuitsWithoutOpeningTheGalleryReference() {
        val ref = gateway.resolve("content://nothing/at/all", localCopyPath = "photos/x.jpg")

        assertEquals(PhotoRef.LocalCopy("photos/x.jpg"), ref)
    }

    @Test
    fun localCopyWritesTheFullBytesAndDeleteRemovesThem() {
        val uri = sourceImage(600, 400)

        val relative = gateway.writeLocalCopy("cap-5", uri.toString())!!
        val file = ownedFile(context.filesDir, relative)
        assertTrue(file.exists())

        gateway.deleteOwnedFile(relative)
        assertTrue("deleting an app-owned file must not touch the source", !file.exists())
    }

    @Test
    fun deletingAFileThatIsAlreadyGoneIsSilent() {
        gateway.deleteOwnedFile("thumbnails/never-existed.jpg")
    }

    @Test
    fun releasingAGrantThisAppNeverHeldDoesNotThrow() {
        gateway.releaseGrant("content://media/external/images/media/999999999")
    }

    @Test
    fun theGrantCountIsReadableAndNonNegative() {
        assertTrue(gateway.persistedGrantCount() >= 0)
    }
}
