package dev.tlong.biodex.data.backup

import kotlinx.serialization.Serializable

/**
 * The on-disk shape of an S01 archive: `manifest.json` plus the two file trees it names.
 *
 * This is the only format in the app that has to survive a phone being lost, so it is
 * deliberately plain — no Room types, no enums-by-ordinal, every field self-describing —
 * and it carries the *outcome* of the export rather than the intent. A capture's
 * [BackupCapture.photoEntry] is non-null only when those bytes really are in the ZIP
 * (see `buildManifest`): the archive never claims a photo it does not hold, because a
 * backup that lies is worse than no backup at all.
 */

/** Bumped only when a reader would need to behave differently. */
const val BACKUP_FORMAT_VERSION = 1

const val MANIFEST_ENTRY = "manifest.json"

fun thumbnailArchiveEntry(captureId: String): String = "thumbnails/$captureId.jpg"

fun photoArchiveEntry(captureId: String): String = "photos/$captureId.jpg"

@Serializable
data class BackupManifest(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val app: String = "BioDex",
    val exportedAt: Long,
    val regionId: String,
    val species: List<BackupSpecies> = emptyList(),
    val entries: List<BackupEntry> = emptyList(),
    val captures: List<BackupCapture> = emptyList(),
    val photoReport: PhotoReport = PhotoReport(),
)

/**
 * Curated species are exported too, but only as identity: on a restore they come from the
 * bundled catalogue asset, and an import never invents one (see `planImport`). User-added
 * species carry every field, because nothing else in the world has them.
 */
@Serializable
data class BackupSpecies(
    val id: String,
    val source: String,
    val dexNumber: Int,
    val commonName: String,
    val taxClass: String,
    val silhouetteRes: String,
    val scientificName: String? = null,
    val detailsPending: Boolean = false,
    val habitatText: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val callUrl: String? = null,
    val infoUrl: String? = null,
    val imageAttribution: String? = null,
    val callAttribution: String? = null,
    val userEditedFields: List<String> = emptyList(),
    val ecosystemIds: List<String> = emptyList(),
    // The uses block (11.1). Every field defaults, so a v3 archive — written before plants
    // existed — still parses and restores as the animal it always was.
    val kingdom: String = "animal",
    val uses: List<String> = emptyList(),
    val usesNote: String? = null,
    val medicinalActivities: List<String> = emptyList(),
    val medicinalRecordCount: Int = 0,
    val usesAttribution: String? = null,
)

@Serializable
data class BackupEntry(
    val speciesId: String,
    val caughtAt: Long,
    val favoriteCaptureId: String? = null,
)

@Serializable
data class BackupCapture(
    val id: String,
    val speciesId: String,
    /** The original device's content URI. Kept for provenance; an import never trusts it. */
    val photoUri: String,
    val takenAt: Long,
    val createdAt: Long,
    /** Present exactly when the ZIP holds this file. */
    val thumbEntry: String? = null,
    /** Present exactly when the ZIP holds a full-size copy of the photo. */
    val photoEntry: String? = null,
    /** Why the full-size photo is or is not here — [PhotoDisposition.name]. */
    val photoStatus: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val locationLabel: String? = null,
    val note: String? = null,
)

/** What the user is told, and what the archive records about its own completeness. */
@Serializable
data class PhotoReport(
    val captures: Int = 0,
    val fullSizeIncluded: Int = 0,
    val missingRevoked: Int = 0,
    val missingOffline: Int = 0,
    val missingUnreadable: Int = 0,
    val thumbnailsIncluded: Int = 0,
) {
    val missingTotal: Int get() = missingRevoked + missingOffline + missingUnreadable

    /**
     * The honest headline. A revoked reference is gone for good; an offline one may well
     * export next time, and saying so is the difference between a user who retries and a
     * user who finds out years later.
     */
    val complete: Boolean get() = missingTotal == 0
}

/**
 * Why one capture's full-size photo did or did not make it into the archive.
 *
 * The three failure states are not cosmetic. [MISSING_REVOKED] means the gallery photo is
 * gone — no future export will ever contain it. [MISSING_OFFLINE] is a cloud-only Google
 * Photos item the device could not fetch, which a re-export while online will pick up.
 * [MISSING_UNREADABLE] means the reference resolved but the bytes failed mid-copy.
 */
enum class PhotoDisposition {
    INCLUDED,
    MISSING_REVOKED,
    MISSING_OFFLINE,
    MISSING_UNREADABLE,
}
