package dev.tlong.biodex.data.photo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

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

    override fun resolve(photoUri: String, localCopyPath: String?): PhotoRef =
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

    private fun writeJpeg(bitmap: Bitmap, relativePath: String, quality: Int) {
        val target = File(filesDir, relativePath).also { it.parentFile?.mkdirs() }
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }

    private companion object {
        const val TAG = "PhotoGateway"
    }
}
