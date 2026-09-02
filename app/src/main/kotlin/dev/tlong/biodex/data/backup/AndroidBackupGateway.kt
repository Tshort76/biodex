package dev.tlong.biodex.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.tlong.biodex.data.photo.PhotoGateway
import dev.tlong.biodex.data.photo.PhotoRef
import dev.tlong.biodex.data.photo.ownedFile
import dev.tlong.biodex.domain.Capture
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The platform shell of [BackupGateway]: content resolver, `filesDir`, and the FileProvider
 * that turns the finished ZIP into something the share sheet can hand to another app.
 *
 * There is no branching worth testing here — every decision lives above the interface.
 * Resolution is delegated to the existing [PhotoGateway] rather than re-probed, so an
 * export sees exactly the states the Photo Viewer sees (4.2).
 */
class AndroidBackupGateway(
    private val context: Context,
    private val photos: PhotoGateway,
) : BackupGateway {

    private val filesDir: File get() = context.filesDir

    private val exportsDir: File get() = File(context.cacheDir, EXPORTS_DIR)

    override fun resolvePhoto(capture: Capture): PhotoRef =
        photos.resolve(capture.photoUri, capture.localCopyPath)

    override fun ownedFileExists(relativePath: String): Boolean =
        ownedFile(filesDir, relativePath).isFile

    override fun openOwnedFile(relativePath: String): InputStream? = try {
        ownedFile(filesDir, relativePath).takeIf { it.isFile }?.inputStream()
    } catch (e: IOException) {
        null
    }

    override fun writeOwnedFile(relativePath: String, source: InputStream): Boolean = try {
        val target = ownedFile(filesDir, relativePath)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { source.copyTo(it) }
        true
    } catch (e: IOException) {
        false
    }

    override fun openGalleryPhoto(uri: String): InputStream? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))
    } catch (e: SecurityException) {
        null
    } catch (e: IOException) {
        null
    }

    override fun beginExport(fileName: String): ExportTarget? = try {
        exportsDir.mkdirs()
        // One archive at a time: the previous one has already been shared or abandoned, and
        // leaving them to pile up in the cache directory serves nobody.
        exportsDir.listFiles()?.forEach { it.delete() }
        val file = File(exportsDir, fileName)
        ExportTarget(handle = file.absolutePath, stream = FileOutputStream(file) as OutputStream)
    } catch (e: IOException) {
        null
    }

    override fun shareUriFor(handle: String): String? = try {
        FileProvider.getUriForFile(context, fileProviderAuthority(context), File(handle))
            .toString()
    } catch (e: IllegalArgumentException) {
        null
    }

    override fun openArchive(archiveUri: String): InputStream? = try {
        context.contentResolver.openInputStream(Uri.parse(archiveUri))
    } catch (e: SecurityException) {
        null
    } catch (e: IOException) {
        null
    }
}

/** Matches `android:authorities` in the manifest. */
fun fileProviderAuthority(context: Context): String = "${context.packageName}.files"

const val EXPORTS_DIR = "exports"
