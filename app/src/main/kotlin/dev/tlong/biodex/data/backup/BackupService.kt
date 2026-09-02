package dev.tlong.biodex.data.backup

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * S01: the whole of export and import, above the [BackupGateway] seam.
 *
 * The archive is an ordinary ZIP — `manifest.json`, `thumbnails/<captureId>.jpg`,
 * `photos/<captureId>.jpg` — so any file manager on any machine can open it, and a
 * restore does not depend on this app still existing.
 *
 * Both directions write files before they write the record that names them: the export
 * writes every photo and then builds the manifest from what actually landed, and the import
 * extracts into `filesDir` and then inserts rows describing only the files that arrived.
 * That ordering is the difference between a backup and a story about a backup.
 */
class BackupService(
    private val store: BackupStore,
    private val gateway: BackupGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    sealed interface ExportResult {
        data class Success(
            val shareUri: String,
            val fileName: String,
            val report: PhotoReport,
        ) : ExportResult

        data object NothingToExport : ExportResult

        data class Failed(val reason: String) : ExportResult
    }

    sealed interface ImportResult {
        data class Success(val report: ImportReport) : ImportResult

        data class Failed(val reason: String) : ImportResult
    }

    suspend fun export(): ExportResult {
        val snapshot = store.backupSnapshot()
        if (snapshot.captures.isEmpty() && snapshot.entries.isEmpty()) {
            return ExportResult.NothingToExport
        }

        val refs = snapshot.captures.associate { it.id to gateway.resolvePhoto(it) }
        val items = planExport(snapshot.captures, refs, gateway::ownedFileExists)
        val fileName = exportFileName(now())
        val target = gateway.beginExport(fileName)
            ?: return ExportResult.Failed("Could not create the archive file.")

        val written = mutableSetOf<String>()
        val manifest = try {
            ZipOutputStream(BufferedOutputStream(target.stream)).use { zip ->
                items.forEach { item ->
                    val thumbEntry = item.thumbnailEntry
                    val thumbSource = item.thumbnailSource
                    if (thumbEntry != null && thumbSource != null) {
                        if (writeEntry(zip, thumbEntry) { gateway.openOwnedFile(thumbSource) }) {
                            written += thumbEntry
                        }
                    }
                    val photoEntry = item.photoEntry
                    when (val source = item.photoSource) {
                        is PhotoSource.Owned ->
                            if (writeEntry(zip, photoEntry!!) {
                                    gateway.openOwnedFile(source.relativePath)
                                }
                            ) {
                                written += photoEntry
                            }

                        is PhotoSource.Gallery ->
                            if (writeEntry(zip, photoEntry!!) {
                                    gateway.openGalleryPhoto(source.uri)
                                }
                            ) {
                                written += photoEntry
                            }

                        null -> Unit
                    }
                }
                // Last, and only now: the manifest describes what the archive turned out to
                // hold, so it cannot name a photo whose bytes never made it.
                val built = buildManifest(
                    exportedAt = now(),
                    regionId = snapshot.regionId,
                    species = snapshot.species,
                    entries = snapshot.entries,
                    items = items,
                    writtenEntries = written,
                )
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(json.encodeToString(BackupManifest.serializer(), built).toByteArray())
                zip.closeEntry()
                built
            }
        } catch (e: IOException) {
            return ExportResult.Failed("Could not write the archive: ${e.message}")
        }

        val shareUri = gateway.shareUriFor(target.handle)
            ?: return ExportResult.Failed("The archive was written but could not be shared.")
        return ExportResult.Success(shareUri, fileName, manifest.photoReport)
    }

    suspend fun import(archiveUri: String): ImportResult {
        val manifest = readManifest(archiveUri)
            ?: return ImportResult.Failed("That file is not a BioDex archive.")
        if (manifest.formatVersion > BACKUP_FORMAT_VERSION) {
            return ImportResult.Failed(
                "That archive was written by a newer version of the app.",
            )
        }

        val plan = planImport(manifest, store.localSnapshot())
        val restored = try {
            extractFiles(archiveUri, plan.filesToRestore)
        } catch (e: IOException) {
            return ImportResult.Failed("Could not read the archive: ${e.message}")
        }
        // Files first, rows second: a capture row never names a file that is not on disk.
        val applied = withRestoredFiles(plan, restored)
        store.applyImport(applied)
        return ImportResult.Success(applied.report)
    }

    private fun readManifest(archiveUri: String): BackupManifest? {
        val stream = gateway.openArchive(archiveUri) ?: return null
        return try {
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == MANIFEST_ENTRY) {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        return@use json.decodeFromString(BackupManifest.serializer(), text)
                    }
                    entry = zip.nextEntry
                }
                null
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            // Malformed JSON, or a ZIP that happens to carry a different manifest.json.
            null
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
    }

    /** @return the archive entry names that were successfully written into `filesDir`. */
    private fun extractFiles(archiveUri: String, targets: Map<String, String>): Set<String> {
        if (targets.isEmpty()) return emptySet()
        val stream = gateway.openArchive(archiveUri) ?: return emptySet()
        val restored = mutableSetOf<String>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = targets[entry.name]
                if (target != null && gateway.writeOwnedFile(target, NonClosing(zip))) {
                    restored += entry.name
                }
                entry = zip.nextEntry
            }
        }
        return restored
    }

    /**
     * The bytes are read **before** the ZIP entry is opened, one file at a time. A
     * `ZipOutputStream` entry cannot be un-opened once written to, so streaming straight
     * through would leave a truncated, zero-byte `photos/<id>.jpg` behind whenever a photo
     * failed mid-copy — a file a human browsing the archive would read as a photo. Buffering
     * costs one image in memory and makes failure mean "not there", which is what the
     * manifest then says.
     */
    private inline fun writeEntry(
        zip: ZipOutputStream,
        name: String,
        open: () -> InputStream?,
    ): Boolean {
        val bytes = try {
            open()?.use { it.readBytes() } ?: return false
        } catch (e: IOException) {
            // One unreadable photo must not lose the whole archive; it is reported instead.
            return false
        } catch (e: OutOfMemoryError) {
            return false
        }
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        return true
    }
}

/**
 * A `ZipInputStream` must not be closed between entries, but the code that consumes one
 * entry naturally wants to `use` it. This wrapper makes `close()` a no-op.
 */
private class NonClosing(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

    override fun available(): Int = delegate.available()

    override fun close() = Unit
}

/** `biodex-backup-2026-09-01-0941.zip` — sortable, and readable in a file manager. */
fun exportFileName(at: Long): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(at))
    return "biodex-backup-$stamp.zip"
}
