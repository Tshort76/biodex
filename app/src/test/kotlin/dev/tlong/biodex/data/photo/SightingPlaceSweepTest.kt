package dev.tlong.biodex.data.photo

import dev.tlong.biodex.domain.Capture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D67. The sweep fills coordinates the picker once redacted, and never touches a typed place. */
class SightingPlaceSweepTest {

    private fun capture(id: String, label: String? = null, lat: Double? = null, photo: String? = "content://p/$id") =
        Capture(
            id = id,
            speciesId = "owl",
            photoUri = photo,
            thumbPath = photo?.let { "thumbnails/$id.jpg" },
            takenAt = 1L,
            lat = lat,
            lng = lat?.let { -122.0 },
            locationLabel = label,
            createdAt = 1L,
        )

    private class FakeStore(rows: List<Capture>) : PlaceBackfillStore {
        val captures = rows.associateBy { it.id }.toMutableMap()
        override suspend fun placelessPhotographedCaptures() =
            captures.values.filter { it.photoUri != null && it.lat == null }
        override suspend fun applyPlaceBackfill(plan: PlaceBackfillPlan) {
            val c = captures.getValue(plan.captureId)
            captures[plan.captureId] = c.copy(
                lat = plan.lat,
                lng = plan.lng,
                locationLabel = c.locationLabel ?: plan.locationLabel,
            )
        }
        var done = false
        override suspend fun placeBackfillDone() = done
        override suspend fun markPlaceBackfillDone() { done = true }
    }

    @Test
    fun `placeless photographed sightings gain coordinates and a name`() = runBlocking {
        val store = FakeStore(listOf(capture("a"), capture("b", label = "Bear Valley")))
        val photos = FakePhotoGateway(exif = ExifFacts(lat = 38.0, lng = -122.8))
        val sweep = SightingPlaceSweep(store, photos) { _, _ -> "Point Reyes, CA" }

        assertEquals(2, sweep.run())

        assertEquals(38.0, store.captures.getValue("a").lat!!, 0.0001)
        assertEquals("Point Reyes, CA", store.captures.getValue("a").locationLabel)
        assertEquals("the typed place wins", "Bear Valley", store.captures.getValue("b").locationLabel)
        assertEquals(38.0, store.captures.getValue("b").lat!!, 0.0001)
        assertNull("the second run does not happen at all", sweep.run())
    }

    @Test
    fun `a photo that still reads without GPS is left alone, and coordinates land without a name`() =
        runBlocking {
            val store = FakeStore(listOf(capture("a")))
            val photos = FakePhotoGateway(exif = ExifFacts.None)
            assertEquals(0, SightingPlaceSweep(store, photos).run())
            assertNull(store.captures.getValue("a").lat)

            // The run marked itself done; the one door left is a re-link, which reads again.
            store.done = false
            photos.exif = ExifFacts(lat = 38.0, lng = -122.8)
            assertEquals(1, SightingPlaceSweep(store, photos) { _, _ -> null }.run())
            assertEquals(38.0, store.captures.getValue("a").lat!!, 0.0001)
            assertNull("no geocoder answer is no label", store.captures.getValue("a").locationLabel)
        }

    @Test
    fun `the plan is pure about what it writes`() {
        val facts = ExifFacts(lat = 1.0, lng = 2.0)
        assertNull("already placed", planPlaceBackfill(capture("a", lat = 5.0), facts, "x"))
        assertNull("nothing read", planPlaceBackfill(capture("a"), ExifFacts.None, "x"))
        assertEquals("x", planPlaceBackfill(capture("a"), facts, "x")!!.locationLabel)
        assertNull("typed label kept", planPlaceBackfill(capture("a", label = "Home"), facts, "x")!!.locationLabel)
        assertNull("blank label counts as none", planPlaceBackfill(capture("a", label = " "), facts, null)!!.locationLabel)
    }
}
