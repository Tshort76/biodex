package dev.tlong.animaldex.data.backup

import dev.tlong.animaldex.data.photo.PhotoRef
import dev.tlong.animaldex.domain.Capture
import java.io.InputStream
import java.io.OutputStream

/**
 * The platform half of export and import — the same seam slices 3, 5 and 7 used for the
 * catalogue store, the photo gateway and the JSON fetcher.
 *
 * Everything above this interface (the ZIP layout, the manifest, the merge) is ordinary
 * Kotlin the JVM suite drives end to end with an in-memory fake, so an export/import round
 * trip is a unit test rather than a claim about a phone nobody has connected.
 */
interface BackupGateway {

    /** 4.2's resolution, for deciding whether a full-size photo can be exported at all. */
    fun resolvePhoto(capture: Capture): PhotoRef

    fun ownedFileExists(relativePath: String): Boolean

    fun openOwnedFile(relativePath: String): InputStream?

    /** Writes an app-owned file under `filesDir`, creating parent directories. */
    fun writeOwnedFile(relativePath: String, source: InputStream): Boolean

    /** Opens a gallery reference for reading. Null on any failure — never throws. */
    fun openGalleryPhoto(uri: String): InputStream?

    /** Creates the archive file and returns a handle plus its stream. */
    fun beginExport(fileName: String): ExportTarget?

    /** The `content://` URI the share sheet hands to another app (S01). */
    fun shareUriFor(handle: String): String?

    /**
     * Opens an archive the user picked. Called twice per import — once to read the manifest,
     * once to extract the files it names — because a ZIP stream cannot be rewound.
     */
    fun openArchive(archiveUri: String): InputStream?
}

data class ExportTarget(val handle: String, val stream: OutputStream)
