package dev.tlong.animaldex.data.photo

import dev.tlong.animaldex.domain.Capture
import dev.tlong.animaldex.domain.Entry

/**
 * The decisions registration and deletion make, as pure functions over plain values.
 *
 * Everything in this file is the part of the core loop that must be right for the life list
 * to survive years: whether a registration unlocks a species, whether deleting a photo
 * un-catches it, whether a shared grant may be released. None of it needs Android, so all of
 * it is under JVM test.
 */

/** What one registration writes. [newEntry] is null when the species is already caught. */
data class RegistrationPlan(
    val capture: Capture,
    val newEntry: Entry?,
) {
    /** M09: a first capture plays the unlock reveal; a repeat gets only a "+1". */
    val isFirst: Boolean get() = newEntry != null
}

/**
 * M09. The species is unlocked by its first capture, and `caughtAt` is that capture's
 * registration time — the entry and the capture agree by construction rather than by two
 * clock reads.
 */
fun planRegistration(
    capture: Capture,
    existingEntry: Entry?,
): RegistrationPlan = RegistrationPlan(
    capture = capture,
    newEntry = if (existingEntry == null) {
        Entry(
            speciesId = capture.speciesId,
            caughtAt = capture.createdAt,
            favoriteCaptureId = capture.id,
            captureCount = 1,
        )
    } else {
        null
    },
)

/**
 * What deleting one capture must do (S07). Every field here is a hazard slice 3 or the
 * Android grant model left explicitly open:
 *
 * - [clearFavorite] — `entries.favoriteCaptureId` carries no foreign key (3.4), so a deleted
 *   favorite dangles unless this column is nulled. The DAO's status query then falls back to
 *   the earliest remaining capture, so the grid self-heals.
 * - [deleteEntry] — deleting the last capture reverts the species to uncaught. The *only*
 *   place in the app where a catch is lost, and it happens behind an explicit warning.
 * - [releaseUri] — null when another capture still references the same gallery photo.
 *   Releasing then would break that other capture's reference too (4.4 assumes one grant per
 *   capture; registering the same photo against two species breaks that assumption).
 *
 * The gallery photo itself is never touched.
 */
data class CaptureDeletionPlan(
    val captureId: String,
    val speciesId: String,
    val deleteEntry: Boolean,
    val clearFavorite: Boolean,
    val filesToDelete: List<String>,
    val releaseUri: String?,
)

fun planCaptureDeletion(
    capture: Capture,
    /** Every capture of the same species, including the one being deleted. */
    speciesCaptures: List<Capture>,
    favoriteCaptureId: String?,
    /** Captures anywhere in the database holding this exact `photoUri`, including this one. */
    uriReferenceCount: Int,
): CaptureDeletionPlan {
    val remaining = speciesCaptures.count { it.id != capture.id }
    return CaptureDeletionPlan(
        captureId = capture.id,
        speciesId = capture.speciesId,
        deleteEntry = remaining == 0,
        clearFavorite = favoriteCaptureId == capture.id,
        filesToDelete = listOfNotNull(capture.thumbPath, capture.localCopyPath),
        releaseUri = if (uriReferenceCount <= 1) capture.photoUri else null,
    )
}

/**
 * Re-linking a broken reference (4.2): the capture keeps its identity, gains a new URI, and
 * regenerates its thumbnail into the same path. The old grant is released on the same
 * shared-reference rule as deletion.
 */
data class RelinkPlan(
    val captureId: String,
    val newPhotoUri: String,
    val releaseUri: String?,
)

fun planRelink(
    capture: Capture,
    newPhotoUri: String,
    uriReferenceCount: Int,
): RelinkPlan = RelinkPlan(
    captureId = capture.id,
    newPhotoUri = newPhotoUri,
    releaseUri = if (capture.photoUri != newPhotoUri && uriReferenceCount <= 1) {
        capture.photoUri
    } else {
        null
    },
)

/**
 * 4.4's cap, as the one number the UI reads. Nothing throttles registration — a personal life
 * list cannot reach 5,000 grants when every deletion releases one — but Settings shows this
 * and registration warns rather than failing silently if it ever gets close.
 */
const val PERSISTED_GRANT_CAP = 5_000
const val PERSISTED_GRANT_WARN_AT = 4_500

fun grantPressure(count: Int): GrantPressure = when {
    count >= PERSISTED_GRANT_CAP -> GrantPressure.AT_CAP
    count >= PERSISTED_GRANT_WARN_AT -> GrantPressure.NEAR_CAP
    else -> GrantPressure.FINE
}

enum class GrantPressure { FINE, NEAR_CAP, AT_CAP }
