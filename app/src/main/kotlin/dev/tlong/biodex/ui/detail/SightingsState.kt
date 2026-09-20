package dev.tlong.biodex.ui.detail

import dev.tlong.biodex.domain.Capture
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * D56. One line per capture, newest first, saying **when** and **where** — the record that
 * outlives the photograph. A capture keeps its `takenAt`, its coordinates and its label on the
 * row (M13), so deleting the photo from the gallery, or unlinking it (D61), changes
 * nothing here. Pure, so the JVM suite can pin the wording.
 */
data class SightingRow(
    val captureId: String,
    /** "Aug 30, 2026 · 3:42 PM" — the EXIF moment, or registration time when there was none. */
    val whenText: String,
    /** The typed or geocoded label, else the coordinates, else null for "place unknown". */
    val whereText: String?,
    val hasPhoto: Boolean,
)

private val whenFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

fun sightingRows(
    captures: List<Capture>,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<SightingRow> =
    captures.sortedByDescending { it.takenAt }.map { capture ->
        SightingRow(
            captureId = capture.id,
            whenText = formatSightingTime(capture.takenAt, zone, locale),
            whereText = sightingPlace(capture),
            hasPhoto = capture.thumbPath != null,
        )
    }

internal fun formatSightingTime(epochMillis: Long, zone: ZoneId, locale: Locale): String =
    whenFormat.withLocale(locale).format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** The label when there is one; "37.8044° N, 122.2712° W" when only coordinates survive. */
internal fun sightingPlace(capture: Capture): String? {
    capture.locationLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val lat = capture.lat ?: return null
    val lng = capture.lng ?: return null
    return formatCoordinates(lat, lng)
}

internal fun formatCoordinates(lat: Double, lng: Double): String {
    val ns = if (lat >= 0) "N" else "S"
    val ew = if (lng >= 0) "E" else "W"
    return String.format(Locale.ROOT, "%.4f° %s, %.4f° %s", abs(lat), ns, abs(lng), ew)
}
