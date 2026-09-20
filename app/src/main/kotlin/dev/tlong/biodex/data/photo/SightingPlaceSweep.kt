package dev.tlong.biodex.data.photo

import dev.tlong.biodex.domain.Capture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * D67. Fills in the place on sightings registered before the app could read it. Every capture
 * from before D63 that still has its photo linked was stored with no coordinates — the picker
 * redacted them, and nothing has re-read the file since. Now that the media-store original is
 * reachable, this runs once per start after the catalogue import, re-reads the EXIF of each
 * placeless photographed capture, and writes what it finds. Idempotent: a capture the read
 * cannot place stays as it is and is tried again next start, which costs one stream open.
 *
 * The decision is [planPlaceBackfill]; this class is the read and the write around it.
 */
class SightingPlaceSweep(
    private val store: PlaceBackfillStore,
    private val photos: PhotoGateway,
    private val places: PlaceNamer = PlaceNamer.None,
) {

    /** Returns how many sightings gained a place. */
    suspend fun run(): Int {
        var filled = 0
        for (capture in store.placelessPhotographedCaptures()) {
            val uri = capture.photoUri ?: continue
            val facts = withContext(Dispatchers.IO) { photos.readExif(uri) }
            val label = if (capture.locationLabel == null && facts.lat != null && facts.lng != null) {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(PLACE_NAME_TIMEOUT_MS) { places.nameFor(facts.lat, facts.lng) }
                }
            } else {
                null
            }
            val plan = planPlaceBackfill(capture, facts, label) ?: continue
            store.applyPlaceBackfill(plan)
            filled++
        }
        return filled
    }

    private companion object {
        const val PLACE_NAME_TIMEOUT_MS = 3_000L
    }
}

/** What the sweep needs from the store, narrow so the JVM suite drives it with a fake. */
interface PlaceBackfillStore {
    /** Captures with a photo still linked and no coordinates on the row. */
    suspend fun placelessPhotographedCaptures(): List<Capture>
    suspend fun applyPlaceBackfill(plan: PlaceBackfillPlan)
}

/** One row's fill: the coordinates, and a label only when the row had none. */
data class PlaceBackfillPlan(
    val captureId: String,
    val lat: Double,
    val lng: Double,
    /** Null leaves the stored label alone — a typed place always wins (D56). */
    val locationLabel: String?,
)

/**
 * Pure. Null when there is nothing to write: the row already has coordinates, or the read
 * found none. A typed label is never overwritten, and a geocoder failure still writes the
 * coordinates — the sighting row prints them when there is no name.
 */
fun planPlaceBackfill(capture: Capture, facts: ExifFacts, geocodedLabel: String?): PlaceBackfillPlan? {
    if (capture.lat != null && capture.lng != null) return null
    val lat = facts.lat ?: return null
    val lng = facts.lng ?: return null
    return PlaceBackfillPlan(
        captureId = capture.id,
        lat = lat,
        lng = lng,
        locationLabel = if (capture.locationLabel.isNullOrBlank()) geocodedLabel else null,
    )
}
