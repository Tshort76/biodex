package dev.tlong.biodex.ui.settings

import dev.tlong.biodex.data.backup.ImportReport
import dev.tlong.biodex.data.identify.DEFAULT_MONTHLY_IDENTIFICATION_CAP
import dev.tlong.biodex.data.identify.identificationCountLine
import dev.tlong.biodex.data.backup.PhotoReport
import dev.tlong.biodex.data.photo.GrantPressure
import dev.tlong.biodex.data.photo.PERSISTED_GRANT_CAP
import dev.tlong.biodex.media.CacheSizes
import dev.tlong.biodex.media.formatBytes

/**
 * The Settings screen's state, and the sentences it says.
 *
 * The sentences are pure functions on purpose. The one about a backup is the most
 * consequential piece of text in the app: S01 exists because photos are referenced rather
 * than copied, so a user who is told "exported" when three photos were left behind has been
 * given a false belief about their only protection against losing the phone. What the
 * archive contains, and what it could not contain and why, is therefore under unit test.
 */

data class SettingsUiState(
    val keepLocalCopy: Boolean = false,
    val cacheSizes: CacheSizes = CacheSizes(0, 0),
    val grantCount: Int = 0,
    val grantPressure: GrantPressure = GrantPressure.FINE,
    val busy: SettingsBusy? = null,
    /** The outcome of the last export, import or cache clear, shown until the next one. */
    val message: String? = null,
    val messageIsWarning: Boolean = false,

    // Identification (M37, M39). The key and the month's count.
    val plantNetKey: String = "",
    val identificationsUsed: Int = 0,
    val identificationCap: Int = DEFAULT_MONTHLY_IDENTIFICATION_CAP,
) {
    val hasPlantNetKey: Boolean get() = plantNetKey.isNotBlank()

    val identificationLine: String
        get() = identificationCountLine(identificationsUsed, identificationCap)

    /** Warns in the same register the grant count does, once the cap is actually in the way. */
    val identificationCapReached: Boolean get() = identificationsUsed >= identificationCap
}

/**
 * The privacy sentence, said where the key is pasted rather than only in `licenses.md` (M36,
 * §7). It is deliberately specific about the three things a user would want to know and would
 * otherwise have to take on trust: that nothing goes anywhere until they press the button, that
 * what leaves is a reduced copy rather than their file, and that the location the photo was
 * taken at does not go with it.
 */
const val IDENTIFICATION_PRIVACY_TEXT =
    "Nothing is uploaded unless you press Identify on a photo. What is sent then is a " +
        "reduced copy of that one photo, re-encoded so it carries no location and no other " +
        "metadata — never the original file, and never anything else in your collection. " +
        "BioDex makes no claim about what the service does with it; its terms are the place " +
        "to check."

/** M39/D24, said plainly beside the field so the human step reads as deliberate. */
const val IDENTIFICATION_KEY_TEXT =
    "Identification needs a free Pl@ntNet API key, which you sign up for by email and paste " +
        "here. It is stored on this phone only. No key is built into the app, because this " +
        "app's source is public."

enum class SettingsBusy { EXPORTING, IMPORTING, CLEARING }

sealed interface SettingsEvent {
    /** Hand the finished archive to the share sheet (S01). */
    data class ShareArchive(val uri: String, val fileName: String) : SettingsEvent
}

/**
 * What the user is told after an export. Photos that could not be included are named
 * separately by reason, because the two reasons call for different actions: a revoked
 * reference will never export (the gallery photo is gone), while a cloud-only item usually
 * will on the next try with a connection.
 */
fun exportSummary(fileName: String, report: PhotoReport): String {
    val head = "Saved $fileName — ${report.captures} " +
        (if (report.captures == 1) "photo record" else "photo records") +
        ", ${report.thumbnailsIncluded} thumbnails, " +
        "${report.fullSizeIncluded} full-size photos."
    if (report.complete) {
        return "$head Every photo you still have is in the archive."
    }
    val reasons = buildList {
        if (report.missingRevoked > 0) {
            add(
                "${report.missingRevoked} " +
                    (if (report.missingRevoked == 1) "photo is" else "photos are") +
                    " no longer in your gallery and can never be exported",
            )
        }
        if (report.missingOffline > 0) {
            add(
                "${report.missingOffline} could not be fetched (cloud-only or offline) — " +
                    "exporting again while online should include " +
                    (if (report.missingOffline == 1) "it" else "them"),
            )
        }
        if (report.missingUnreadable > 0) {
            add("${report.missingUnreadable} could not be read")
        }
    }
    return "$head ${report.missingTotal} full-size " +
        (if (report.missingTotal == 1) "photo is" else "photos are") +
        " missing: ${reasons.joinToString("; ")}. " +
        "Their thumbnails and every detail of the catch are still in the archive."
}

fun importSummary(report: ImportReport): String {
    val head = "Restored ${report.capturesAdded} " +
        (if (report.capturesAdded == 1) "capture" else "captures") +
        ", ${report.photosRestored} full-size photos, " +
        "${report.entriesAdded} newly caught species" +
        (if (report.speciesAdded > 0) ", ${report.speciesAdded} of your own species" else "") +
        "."
    val notes = buildList {
        if (report.capturesAlreadyPresent > 0) {
            add("${report.capturesAlreadyPresent} were already here and were left alone")
        }
        if (report.capturesWithoutSpecies > 0) {
            add(
                "${report.capturesWithoutSpecies} could not be restored because this " +
                    "install's catalogue does not have their species",
            )
        }
        if (report.entriesMerged > 0) {
            add("${report.entriesMerged} catch dates were moved earlier to match the archive")
        }
    }
    return if (notes.isEmpty()) head else "$head " + notes.joinToString("; ") + "."
}

fun cacheLine(sizes: CacheSizes): String =
    "Images ${formatBytes(sizes.imageBytes)} · " +
        "Lookups ${formatBytes(sizes.httpBytes)}"

/** 4.4's informational count, plus the warning when it starts to matter. */
fun grantLine(count: Int, pressure: GrantPressure): String = when (pressure) {
    GrantPressure.FINE -> "$count of $PERSISTED_GRANT_CAP photo permissions held."
    GrantPressure.NEAR_CAP ->
        "$count of $PERSISTED_GRANT_CAP photo permissions held — approaching Android's cap."

    GrantPressure.AT_CAP ->
        "$count of $PERSISTED_GRANT_CAP photo permissions held — at Android's cap. " +
            "Delete some captures before registering more."
}
