package dev.tlong.biodex.ui.detail

import dev.tlong.biodex.domain.Capture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * D56. The sightings list is the record that outlives the photograph, so what it says must
 * depend only on what the capture row holds — never on whether the URI still resolves.
 */
class SightingsStateTest {

    private val pacific = ZoneId.of("America/Los_Angeles")

    // 2026-08-30 15:42 PDT
    private val afternoon = 1_788_129_720_000L

    private fun capture(
        id: String,
        takenAt: Long,
        lat: Double? = null,
        lng: Double? = null,
        label: String? = null,
        photo: Boolean = true,
    ) = Capture(
        id = id,
        speciesId = "owl",
        photoUri = if (photo) "content://media/$id" else null,
        thumbPath = if (photo) "thumbnails/$id.jpg" else null,
        takenAt = takenAt,
        lat = lat,
        lng = lng,
        locationLabel = label,
        createdAt = takenAt,
    )

    @Test
    fun `a row says the date and the time of day`() {
        val rows = sightingRows(listOf(capture("a", afternoon)), pacific, Locale.US)
        assertEquals("Aug 30, 2026 · 3:42 PM", rows.single().whenText)
    }

    @Test
    fun `the label wins, coordinates stand in for it, and nothing is still nothing`() {
        val rows = sightingRows(
            listOf(
                capture("labelled", afternoon, lat = 37.8, lng = -122.27, label = "Lake Merritt"),
                capture("gps-only", afternoon - 1, lat = 37.8044, lng = -122.2712),
                capture("blank-label", afternoon - 2, lat = 1.0, lng = 2.0, label = "  "),
                capture("bare", afternoon - 3),
            ),
            pacific,
            Locale.US,
        )
        assertEquals("Lake Merritt", rows[0].whereText)
        assertEquals("37.8044° N, 122.2712° W", rows[1].whereText)
        assertEquals("1.0000° N, 2.0000° E", rows[2].whereText)
        assertNull(rows[3].whereText)
    }

    @Test
    fun `newest first, and a photoless catch is a row like any other`() {
        val rows = sightingRows(
            listOf(
                capture("old", afternoon - 86_400_000L, photo = false),
                capture("new", afternoon),
            ),
            pacific,
            Locale.US,
        )
        assertEquals(listOf("new", "old"), rows.map { it.captureId })
        assertEquals(listOf(true, false), rows.map { it.hasPhoto })
    }

    @Test
    fun `a photo the gallery has since lost changes nothing here`() {
        // Same row, URI or not: the list reads takenAt, lat, lng and the label — all on the row.
        val kept = capture("x", afternoon, lat = 44.0, lng = -121.3)
        val lost = kept.copy(photoUri = null)
        val a = sightingRows(listOf(kept), pacific, Locale.US).single()
        val b = sightingRows(listOf(lost), pacific, Locale.US).single()
        assertEquals(a.whenText, b.whenText)
        assertEquals(a.whereText, b.whereText)
    }
}
