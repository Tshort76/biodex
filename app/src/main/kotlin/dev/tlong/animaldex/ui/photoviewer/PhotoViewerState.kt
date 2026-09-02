package dev.tlong.animaldex.ui.photoviewer

import dev.tlong.animaldex.data.photo.PhotoRef
import dev.tlong.animaldex.domain.Capture
import dev.tlong.animaldex.domain.Entry
import dev.tlong.animaldex.domain.SpeciesDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Screen 6 (DESIGN.md §6): one photo full-screen, with the date, the note, delete (S07) and
 * set-favorite (S04). This is the only screen that resolves a gallery URI to full size, and so
 * the only screen where M12's three states are visible.
 */

/** What the user is told and offered, derived from the reference's state (4.2, M12). */
data class PhotoAvailability(
    val bannerText: String?,
    val offerRelink: Boolean,
    val showFullSize: Boolean,
)

/**
 * The mapping M12 turns on. A revoked reference is permanent, so it offers a re-link; a
 * cloud-only one is transient, so it says to reconnect and does not. Neither ever hides the
 * stored thumbnail, and neither touches the entry.
 */
fun availabilityFor(ref: PhotoRef?): PhotoAvailability = when (ref) {
    null -> PhotoAvailability(bannerText = null, offerRelink = false, showFullSize = false)

    is PhotoRef.Available, is PhotoRef.LocalCopy ->
        PhotoAvailability(bannerText = null, offerRelink = false, showFullSize = true)

    PhotoRef.Revoked -> PhotoAvailability(
        bannerText = "Full photo unavailable — it was removed from your gallery, or the app's " +
            "access to it was withdrawn. Your catch is safe; re-link a photo to restore it.",
        offerRelink = true,
        showFullSize = false,
    )

    PhotoRef.Unavailable -> PhotoAvailability(
        bannerText = "Photo is in the cloud — connect to load it. Nothing is lost; this view " +
            "retries next time.",
        offerRelink = false,
        showFullSize = false,
    )
}

data class PhotoViewerUiState(
    val capture: Capture? = null,
    val speciesName: String = "",
    val speciesId: String? = null,
    val ref: PhotoRef? = null,
    val isFavorite: Boolean = false,
    /** S07's warning: deleting this photo reverts the species to uncaught. */
    val isLastCapture: Boolean = false,
    val loading: Boolean = true,
) {
    val availability: PhotoAvailability get() = availabilityFor(ref)

    val missing: Boolean get() = !loading && capture == null

    /**
     * The stored thumbnail is always what the screen falls back to (M11/M12) — it is the one
     * rendering of the capture the app owns.
     */
    val thumbPath: String? get() = capture?.thumbPath
}

fun photoViewerUiState(
    capture: Flow<Capture?>,
    speciesDetail: Flow<SpeciesDetail?>,
    entry: Flow<Entry?>,
    ref: Flow<PhotoRef?>,
): Flow<PhotoViewerUiState> =
    combine(capture, speciesDetail, entry, ref) { cap, detail, entryRow, photoRef ->
        PhotoViewerUiState(
            capture = cap,
            speciesName = detail?.summary?.commonName.orEmpty(),
            speciesId = cap?.speciesId,
            ref = photoRef,
            isFavorite = cap != null && entryRow?.favoriteCaptureId == cap.id,
            isLastCapture = entryRow != null && entryRow.captureCount <= 1,
            loading = false,
        )
    }
