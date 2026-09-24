package dev.tlong.biodex.data.photo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
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

    /**
     * D63. The picker's stream is redacted, so for a local photo the original is read first —
     * the media-store row behind the picker id, opened with `setRequireOriginal`, which the
     * store honours once the app holds the photo-library permission. Any refusal (no
     * permission, a cloud item, a row that is gone) falls back to the picker's own stream, and
     * the answer is whichever read carried the coordinates.
     */
    override fun readExif(uri: String): ExifFacts {
        val parsed = Uri.parse(uri)
        val fromOriginal = originalFor(parsed)?.let { readExifFrom(it, "original") }
        if (fromOriginal?.lat != null) return fromOriginal
        val fromStream = readExifFrom(parsed, parsed.authority ?: "?")
        return fromOriginal?.copy(lat = fromStream.lat, lng = fromStream.lng) ?: fromStream
    }

    /** The unredacted media-store URI behind a picker URI, or null when there is none to try. */
    private fun originalFor(picker: Uri): Uri? {
        val id = mediaStoreIdFromPickerUri(picker.toString()) ?: return null
        if (!hasPhotoLibraryAccess(context)) return null
        return MediaStore.setRequireOriginal(
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
        )
    }

    private fun readExifFrom(uri: Uri, label: String): ExifFacts = try {
        resolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            // Null for a gallery-picker stream: the picker redacts GPS (R3, D57). Present for a
            // Files-picker document (D58) or the media-store original (D63) once the
            // permissions are held.
            val latLng = exif.latLong
            Log.i(TAG, "EXIF via $label for $uri: gps=${latLng != null}")
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
        Log.i(TAG, "No EXIF readable via $label from $uri: ${e.message}")
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

/**
 * D63. Whether the media store will hand this app an unredacted original: the library
 * permission (or, from Android 14, the "selected photos" partial grant) plus the media-location
 * permission. Both are asked for on the first gallery tap.
 */
fun hasPhotoLibraryAccess(context: Context): Boolean {
    fun held(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val library = held(Manifest.permission.READ_MEDIA_IMAGES) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            held(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    return library && held(Manifest.permission.ACCESS_MEDIA_LOCATION)
}

/** The permissions the gallery tap asks for (D63), in the order Android lists them. */
fun photoLibraryPermissions(): Array<String> = buildList {
    add(Manifest.permission.READ_MEDIA_IMAGES)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
}.toTypedArray()
