package dev.tlong.animaldex.data.backup

import dev.tlong.animaldex.data.photo.PhotoRef
import dev.tlong.animaldex.data.photo.thumbnailRelativePath
import dev.tlong.animaldex.domain.Capture

/**
 * The two decisions an export makes, as pure functions: what to *try* to write, and what the
 * manifest may *claim* afterwards.
 *
 * They are separate on purpose. A photo that resolves when the plan is made can still fail
 * while its bytes are being copied — a cloud item that goes away mid-stream, a revoked grant
 * between two calls. So the plan is optimistic and the manifest is built from the set of
 * entries the writer actually produced: the archive cannot name a file it does not contain.
 */

/** One capture's place in the archive, before anything is written. */
data class ExportItem(
    val capture: Capture,
    /** The app-owned thumbnail's path relative to `filesDir`, when the file exists. */
    val thumbnailSource: String?,
    /** Where full-size bytes would come from, and null when there are none to be had. */
    val photoSource: PhotoSource?,
    /** What the plan expects; the manifest may downgrade it. */
    val disposition: PhotoDisposition,
) {
    val thumbnailEntry: String? get() = thumbnailSource?.let { thumbnailArchiveEntry(capture.id) }
    val photoEntry: String? get() = photoSource?.let { photoArchiveEntry(capture.id) }
}

/**
 * Where the full-size bytes live. The distinction matters to the writer, not to the user:
 * an S03 local copy is an app-owned file, a gallery reference goes through the content
 * resolver and can fail in ways a file cannot.
 */
sealed interface PhotoSource {
    /** Path relative to `filesDir` (S03's local copy). */
    data class Owned(val relativePath: String) : PhotoSource

    /** A content URI whose grant resolved at plan time. */
    data class Gallery(val uri: String) : PhotoSource
}

/**
 * S01's core rule: *every photo whose reference still resolves* goes into the archive at
 * full size. A resolvable reference is either an own local copy or a live gallery grant;
 * the two broken states of 4.2 map onto the two honest "why not" answers.
 *
 * @param refs   the resolution of each capture, keyed by capture id (4.2).
 * @param ownedFileExists whether an app-owned relative path has a file behind it. A
 *        thumbnail is normally guaranteed to exist (it is written before the capture row),
 *        but an archive must not claim one that a data-clear or a bad restore removed.
 */
fun planExport(
    captures: List<Capture>,
    refs: Map<String, PhotoRef>,
    ownedFileExists: (String) -> Boolean,
): List<ExportItem> = captures.map { capture ->
    val thumbPath = capture.thumbPath.takeIf(ownedFileExists)
        ?: thumbnailRelativePath(capture.id).takeIf(ownedFileExists)
    val source: PhotoSource?
    val disposition: PhotoDisposition
    when (val ref = refs[capture.id]) {
        is PhotoRef.LocalCopy -> if (ownedFileExists(ref.relativePath)) {
            source = PhotoSource.Owned(ref.relativePath)
            disposition = PhotoDisposition.INCLUDED
        } else {
            // The row says a local copy exists and the file does not. Nothing to copy, and
            // the gallery reference was never probed — treat it as unreadable, not as gone.
            source = null
            disposition = PhotoDisposition.MISSING_UNREADABLE
        }

        is PhotoRef.Available -> {
            source = PhotoSource.Gallery(ref.uri)
            disposition = PhotoDisposition.INCLUDED
        }

        PhotoRef.Revoked -> {
            source = null
            disposition = PhotoDisposition.MISSING_REVOKED
        }

        PhotoRef.Unavailable -> {
            source = null
            disposition = PhotoDisposition.MISSING_OFFLINE
        }

        null -> {
            source = null
            disposition = PhotoDisposition.MISSING_UNREADABLE
        }
    }
    ExportItem(
        capture = capture,
        thumbnailSource = thumbPath,
        photoSource = source,
        disposition = disposition,
    )
}

/**
 * Builds the manifest from what the writer actually wrote.
 *
 * [writtenEntries] is the set of ZIP entry names that were successfully produced. Every
 * `thumbEntry` and `photoEntry` in the result is filtered through it, so the invariant
 * "the manifest never names a file the archive does not contain" holds by construction
 * rather than by care. A planned-but-unwritten photo is reported as
 * [PhotoDisposition.MISSING_UNREADABLE] — the bytes were there when we looked and were not
 * there when we copied, which is exactly what the user should be told.
 */
fun buildManifest(
    exportedAt: Long,
    regionId: String,
    species: List<BackupSpecies>,
    entries: List<BackupEntry>,
    items: List<ExportItem>,
    writtenEntries: Set<String>,
): BackupManifest {
    val captures = items.map { item ->
        val thumbEntry = item.thumbnailEntry?.takeIf { it in writtenEntries }
        val photoEntry = item.photoEntry?.takeIf { it in writtenEntries }
        val status = when {
            photoEntry != null -> PhotoDisposition.INCLUDED
            item.disposition == PhotoDisposition.INCLUDED -> PhotoDisposition.MISSING_UNREADABLE
            else -> item.disposition
        }
        BackupCapture(
            id = item.capture.id,
            speciesId = item.capture.speciesId,
            photoUri = item.capture.photoUri,
            takenAt = item.capture.takenAt,
            createdAt = item.capture.createdAt,
            thumbEntry = thumbEntry,
            photoEntry = photoEntry,
            photoStatus = status.name,
            lat = item.capture.lat,
            lng = item.capture.lng,
            locationLabel = item.capture.locationLabel,
            note = item.capture.note,
        )
    }
    return BackupManifest(
        exportedAt = exportedAt,
        regionId = regionId,
        species = species,
        entries = entries,
        captures = captures,
        photoReport = photoReportOf(captures),
    )
}

fun photoReportOf(captures: List<BackupCapture>): PhotoReport = PhotoReport(
    captures = captures.size,
    fullSizeIncluded = captures.count { it.photoEntry != null },
    missingRevoked = captures.count { it.photoStatus == PhotoDisposition.MISSING_REVOKED.name },
    missingOffline = captures.count { it.photoStatus == PhotoDisposition.MISSING_OFFLINE.name },
    missingUnreadable = captures.count {
        it.photoStatus == PhotoDisposition.MISSING_UNREADABLE.name
    },
    thumbnailsIncluded = captures.count { it.thumbEntry != null },
)
