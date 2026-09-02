package dev.tlong.biodex.data.backup

import dev.tlong.biodex.data.photo.PhotoRef
import dev.tlong.biodex.domain.Capture
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * An in-memory phone. `filesDir` is a map, the gallery is a map, and an archive is a byte
 * array — which is what lets the JVM suite run a real export through a real `ZipOutputStream`
 * and then import the result back, with no device anywhere.
 */
class FakeBackupGateway(
    val ownedFiles: MutableMap<String, ByteArray> = mutableMapOf(),
    /** URI → bytes. A null value is a reference that resolves and then fails to open. */
    val gallery: MutableMap<String, ByteArray?> = mutableMapOf(),
    val refs: MutableMap<String, PhotoRef> = mutableMapOf(),
) : BackupGateway {

    val archives: MutableMap<String, ByteArray> = mutableMapOf()
    private val openSinks: MutableMap<String, ByteArrayOutputStream> = mutableMapOf()

    /** Set to fail `beginExport`, the one failure the export path cannot recover from. */
    var refuseExport: Boolean = false

    override fun resolvePhoto(capture: Capture): PhotoRef =
        refs[capture.id] ?: PhotoRef.Unavailable

    override fun ownedFileExists(relativePath: String): Boolean =
        ownedFiles.containsKey(relativePath)

    override fun openOwnedFile(relativePath: String): InputStream? =
        ownedFiles[relativePath]?.let(::ByteArrayInputStream)

    override fun writeOwnedFile(relativePath: String, source: InputStream): Boolean {
        ownedFiles[relativePath] = source.readBytes()
        return true
    }

    override fun openGalleryPhoto(uri: String): InputStream? {
        if (!gallery.containsKey(uri)) return null
        return gallery[uri]?.let(::ByteArrayInputStream) ?: FailingStream()
    }

    override fun beginExport(fileName: String): ExportTarget? {
        if (refuseExport) return null
        val sink = ByteArrayOutputStream()
        openSinks[fileName] = sink
        return ExportTarget(handle = fileName, stream = ClosingSink(fileName, sink))
    }

    override fun shareUriFor(handle: String): String? =
        if (archives.containsKey(handle)) "archive://$handle" else null

    override fun openArchive(archiveUri: String): InputStream? =
        archives[archiveUri.removePrefix("archive://")]?.let(::ByteArrayInputStream)

    /** The ZIP is only complete once its stream closes, so that is when it is published. */
    private inner class ClosingSink(
        private val handle: String,
        private val sink: ByteArrayOutputStream,
    ) : OutputStream() {
        override fun write(b: Int) = sink.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = sink.write(b, off, len)

        override fun close() {
            archives[handle] = sink.toByteArray()
        }
    }

    private class FailingStream : InputStream() {
        override fun read(): Int = throw IOException("the photo went away mid-copy")
    }
}

class FakeBackupStore(
    var snapshot: BackupSnapshot = BackupSnapshot("pacific", emptyList(), emptyList(), emptyList()),
    var local: LocalSnapshot = LocalSnapshot(),
) : BackupStore {

    var applied: ImportPlan? = null

    override suspend fun backupSnapshot(): BackupSnapshot = snapshot

    override suspend fun localSnapshot(): LocalSnapshot = local

    override suspend fun applyImport(plan: ImportPlan) {
        applied = plan
    }
}

fun capture(
    id: String,
    speciesId: String = "western-screech-owl",
    photoUri: String = "content://media/$id",
    thumbPath: String = "thumbnails/$id.jpg",
    localCopyPath: String? = null,
    createdAt: Long = 1_000L,
) = Capture(
    id = id,
    speciesId = speciesId,
    photoUri = photoUri,
    thumbPath = thumbPath,
    localCopyPath = localCopyPath,
    takenAt = createdAt,
    locationLabel = null,
    note = null,
    createdAt = createdAt,
)
