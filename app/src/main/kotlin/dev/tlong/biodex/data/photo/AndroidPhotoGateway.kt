package dev.tlong.biodex.data.photo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID

/**
 * The platform shell of the photo layer (ARCHITECTURE.md 4.1–4.4). Deliberately dumb: every
 * branch that is a *decision* lives above this class, and everything here is a call into
 * Android wrapped so it reports rather than throws.
 *
 * **None of this can be verified without a phone.** The picker grant, `ImageDecoder` and the
 * content resolver's failure modes are the parts of slice 5 the JVM suite cannot reach, which
 * is why the surface is this narrow.
 */
class AndroidPhotoGateway(
    private val context: Context,
    private val filesDir: File = context.filesDir,
) : PhotoGateway {

    private val resolver get() = context.contentResolver

    override fun persistGrant(uri: String): Boolean = try {
        resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        // 4.1: some picker URIs offer no persistable grant. Registration continues; the
        // thumbnail is the durable artifact either way.
        Log.i(TAG, "No persistable grant for $uri: ${e.message}")
        false
    }

    override fun releaseGrant(uri: String) {
        try {
            resolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.i(TAG, "Grant for $uri was already gone: ${e.message}")
        }
    }

    override fun persistedGrantCount(): Int = resolver.persistedUriPermissions.size

    override fun readExif(uri: String): ExifFacts = try {
        resolver.openInputStream(Uri.parse(uri))?.use { stream ->
            val exif = ExifInterface(stream)
            // Usually null: the system picker redacts GPS (risk R3). Ordinary, not an error.
            val latLng = exif.latLong
            ExifFacts(
                takenAt = parseExifDateTime(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                ),
                lat = latLng?.get(0),
                lng = latLng?.get(1),
            )
        } ?: ExifFacts.None
    } catch (e: Exception) {
        Log.i(TAG, "No EXIF readable from $uri: ${e.message}")
        ExifFacts.None
    }

    override fun writeThumbnail(captureId: String, uri: String): String? = try {
        val source = ImageDecoder.createSource(resolver, Uri.parse(uri))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longEdge = maxOf(info.size.width, info.size.height)
            if (longEdge > THUMBNAIL_LONG_EDGE_PX) {
                val scale = THUMBNAIL_LONG_EDGE_PX.toFloat() / longEdge
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        val relative = thumbnailRelativePath(captureId)
        writeJpeg(bitmap, relative, quality = THUMBNAIL_QUALITY)
        bitmap.recycle()
        relative
    } catch (e: Exception) {
        Log.w(TAG, "Thumbnail generation failed for $uri: ${e.message}")
        null
    }

    override fun writeLocalCopy(captureId: String, uri: String): String? = try {
        val relative = localCopyRelativePath(captureId)
        val target = File(filesDir, relative).also { it.parentFile?.mkdirs() }
        resolver.openInputStream(Uri.parse(uri))?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: return null
        relative
    } catch (e: Exception) {
        Log.w(TAG, "Local copy failed for $uri: ${e.message}")
        null
    }

    override fun deleteOwnedFile(relativePath: String) {
        runCatching { File(filesDir, relativePath).delete() }
            .onFailure { Log.i(TAG, "Could not delete $relativePath: ${it.message}") }
    }

    override fun resolve(photoUri: String?, localCopyPath: String?): PhotoRef =
        resolvePhotoRef(photoUri, localCopyPath, ::probeFailure)

    /** Opened and immediately closed as a probe (4.2); Coil then loads the URI itself. */
    private fun probeFailure(uri: String): Throwable? = try {
        val stream = resolver.openInputStream(Uri.parse(uri))
        if (stream == null) {
            FileNotFoundException("content resolver returned no stream for $uri")
        } else {
            stream.close()
            null
        }
    } catch (e: Exception) {
        e
    }

    override fun displayName(uri: String): String? = try {
        resolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    } catch (e: Exception) {
        Log.i(TAG, "No display name for $uri: ${e.message}")
        null
    }

    /**
     * M36's re-encode. It shares `writeThumbnail`'s decode-with-a-target-size shape because
     * that is the shape that never allocates the full-resolution bitmap — a 50 MP phone photo
     * decoded whole is an out-of-memory kill on the one screen where the user is standing in
     * front of the plant.
     */
    override fun readForUpload(uri: String): ByteArray? = try {
        val source = ImageDecoder.createSource(resolver, Uri.parse(uri))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val longEdge = maxOf(info.size.width, info.size.height)
            if (longEdge > UPLOAD_LONG_EDGE_PX) {
                val scale = UPLOAD_LONG_EDGE_PX.toFloat() / longEdge
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, out)
        bitmap.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        Log.w(TAG, "Upload copy failed for $uri: ${e.message}")
        null
    }

    // -----------------------------------------------------------------------
    // The in-app camera (M40). None of this is verified on a phone: whether a
    // given camera app honours a FileProvider EXTRA_OUTPUT is the one claim of
    // §6.6 that documentation cannot settle, because camera apps vary.
    // -----------------------------------------------------------------------

    override fun newCameraCaptureUri(): String? = try {
        val target = File(context.cacheDir, cameraCacheRelativePath(UUID.randomUUID().toString()))
        target.parentFile?.mkdirs()
        target.createNewFile()
        FileProvider.getUriForFile(context, "${context.packageName}.files", target).toString()
    } catch (e: Exception) {
        Log.w(TAG, "Could not make a camera capture file: ${e.message}")
        null
    }

    override fun promoteToGallery(cacheUri: String, displayName: String): String? = try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_SUBDIRECTORY)
        }
        val inserted = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        )
        if (inserted == null) {
            null
        } else {
            resolver.openInputStream(Uri.parse(cacheUri))?.use { input ->
                resolver.openOutputStream(inserted)?.use { output -> input.copyTo(output) }
            }
            inserted.toString()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not promote $cacheUri into the gallery: ${e.message}")
        null
    }

    override fun sweepCameraCache() {
        runCatching { File(context.cacheDir, CAMERA_CACHE_DIR).listFiles()?.forEach { it.delete() } }
            .onFailure { Log.i(TAG, "Camera cache sweep failed: ${it.message}") }
    }

    private fun writeJpeg(bitmap: Bitmap, relativePath: String, quality: Int) {
        val target = File(filesDir, relativePath).also { it.parentFile?.mkdirs() }
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }

    private companion object {
        const val TAG = "PhotoGateway"

        /** Its own album, so a promoted photo is findable and never mixed into Camera/. */
        const val GALLERY_SUBDIRECTORY = "Pictures/BioDex"
    }
}
