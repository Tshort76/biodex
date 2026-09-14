package dev.tlong.biodex.ui.settings

import dev.tlong.biodex.data.backup.ImportReport
import dev.tlong.biodex.data.backup.PhotoReport
import dev.tlong.biodex.data.photo.GrantPressure
import dev.tlong.biodex.data.photo.PERSISTED_GRANT_CAP
import dev.tlong.biodex.media.CacheSizes
import dev.tlong.biodex.media.formatBytes
import dev.tlong.biodex.ui.grid.DexSort

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
    /** D47: the order the grid opens in, and the order the Sort dropdown last wrote. */
    val dexSort: DexSort = DexSort.DEFAULT,
    val cacheSizes: CacheSizes = CacheSizes(0, 0),
    val grantCount: Int = 0,
    val grantPressure: GrantPressure = GrantPressure.FINE,
    val busy: SettingsBusy? = null,
    /** The outcome of the last export, import or cache clear, shown until the next one. */
    val message: String? = null,
    val messageIsWarning: Boolean = false,
)

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
        "${report.fullSizeIncluded} full-size photos." +
        // Said as a fact about the catches, never as a shortfall in the archive: a catch
        // registered without a photograph (a v6–v20 plant, or a photoless add) has nothing
        // to export, so an archive that holds none of them is complete.
        if (report.neverHadPhoto > 0) {
            " ${report.neverHadPhoto} " +
                (if (report.neverHadPhoto == 1) "catch keeps" else "catches keep") +
                " no photo of your own."
        } else {
            ""
        }
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
        // D59. Said plainly: the archive is older than the decision, and the user should
        // hear it from the import rather than notice a missing species later.
        if (report.plantSpeciesSkipped > 0) {
            add(
                "${report.plantSpeciesSkipped} of your own " +
                    (if (report.plantSpeciesSkipped == 1) "species was" else "species were") +
                    " a plant and not restored — BioDex no longer keeps plants",
            )
        }
    }
    return if (notes.isEmpty()) head else "$head " + notes.joinToString("; ") + "."
}

/**
 * D49's dialog, said in full. It names the amount, because "clear the caches" means nothing
 * until you know whether that is 2 MB or 200; it names the cost, which is a slow silent refetch
 * rather than a loss; and it repeats what is *not* touched, because that is the fear the button
 * triggers. Pure, so the sentence is under test rather than trusted.
 */
fun clearCacheConfirmBody(sizes: CacheSizes): String {
    val total = formatBytes(sizes.imageBytes + sizes.httpBytes)
    return "This throws away $total of downloaded pictures and lookups. Every picture is " +
        "fetched again the next time you look at it, which needs a connection and takes a " +
        "while across the whole dex. Your photos, thumbnails and entries are not touched."
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
