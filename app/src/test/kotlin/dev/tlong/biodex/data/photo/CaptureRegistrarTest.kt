package dev.tlong.biodex.data.photo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core loop's data-safety invariants (M09–M13, S04, S07). Each test names a way the
 * collection could quietly lose something: a catch, a thumbnail, another capture's grant.
 */
class CaptureRegistrarTest {

    private val store = FakeCaptureStore()
    private val photos = FakePhotoGateway()
    private var ids = 0
    private var clock = 1_000L

    private val registrar = CaptureRegistrar(
        store = store,
        photos = photos,
        newCaptureId = { "cap-${++ids}" },
        now = { clock },
    )

    // -- Registration --------------------------------------------------------

    @Test
    fun `the typed place wins, GPS is named only when nothing was typed (D56)`() = runBlocking {
        var asked = 0
        val naming = CaptureRegistrar(
            store = store,
            photos = photos,
            newCaptureId = { "cap-${++ids}" },
            now = { clock },
            places = { _, _ -> asked++; "Point Reyes, CA" },
        )
        photos.exif = ExifFacts(takenAt = 42L, lat = 38.07, lng = -122.8)

        val typed = naming.register("owl", "content://photos/1", locationLabel = "Bear Valley")
            as CaptureRegistrar.RegisterResult.Registered
        assertEquals("Bear Valley", store.captures.getValue(typed.captureId).locationLabel)
        assertEquals("the geocoder is not consulted over the user's words", 0, asked)

        val named = naming.register("owl", "content://photos/2")
            as CaptureRegistrar.RegisterResult.Registered
        assertEquals("Point Reyes, CA", store.captures.getValue(named.captureId).locationLabel)
        assertEquals(38.07, store.captures.getValue(named.captureId).lat!!, 0.0001)

        photos.exif = ExifFacts.None
        val bare = naming.register("owl", "content://photos/3", locationLabel = "Bear Valley")
            as CaptureRegistrar.RegisterResult.Registered
        assertEquals("Bear Valley", store.captures.getValue(bare.captureId).locationLabel)
        assertEquals("no coordinates, nothing to name", 1, asked)
    }

    @Test
    fun `no typed place and no GPS writes nothing and hands the grant back (D60)`() = runBlocking {
        photos.exif = ExifFacts(takenAt = 42L)
        val result = registrar.register("owl", "content://photos/1")
        assertEquals(CaptureRegistrar.RegisterResult.PlaceMissing, result)
        assertTrue("no row", store.captures.isEmpty())
        assertFalse("no unlock", store.entries.containsKey("owl"))
        assertEquals("no thumbnail written", emptyList<String>(), photos.writtenThumbnails)
        assertEquals("the grant taken for the attempt is released", listOf("content://photos/1"), photos.released)

        // A blank label is no label.
        assertEquals(
            CaptureRegistrar.RegisterResult.PlaceMissing,
            registrar.register("owl", "content://photos/1", locationLabel = "  "),
        )
        // Coordinates alone are a place: no geocoder is required for the row to be written.
        photos.exif = ExifFacts(lat = 44.0, lng = -121.3)
        assertTrue(registrar.register("owl", "content://photos/1") is CaptureRegistrar.RegisterResult.Registered)
    }

    @Test
    fun `a promoted camera shot reads its EXIF from the cache file it was promoted from (D60)`() =
        runBlocking {
            registrar.register(
                "owl",
                "content://media/external/images/promoted-1",
                exifUri = "content://dev.tlong.biodex.files/capture/1.jpg",
            )
            assertEquals(listOf("content://dev.tlong.biodex.files/capture/1.jpg"), photos.exifReads)
            assertEquals(
                "the row still references the gallery copy",
                "content://media/external/images/promoted-1",
                store.captures.values.single().photoUri,
            )
        }

    @Test
    fun `a grant another capture holds is not released when a place is missing (D60)`() = runBlocking {
        registrar.register("owl", "content://photos/shared")
        photos.released.clear()
        photos.exif = ExifFacts.None
        registrar.register("frog", "content://photos/shared")
        assertEquals(emptyList<String>(), photos.released)
    }

    @Test
    fun `a geocoder that fails leaves the label null and the coordinates intact (D56)`() =
        runBlocking {
            val failing = CaptureRegistrar(
                store = store,
                photos = photos,
                newCaptureId = { "cap-${++ids}" },
                now = { clock },
                places = { _, _ -> null },
            )
            photos.exif = ExifFacts(takenAt = 42L, lat = 38.07, lng = -122.8)
            val r = failing.register("owl", "content://photos/1")
                as CaptureRegistrar.RegisterResult.Registered
            val row = store.captures.getValue(r.captureId)
            assertNull(row.locationLabel)
            assertEquals(-122.8, row.lng!!, 0.0001)
        }

    @Test
    fun `the first capture unlocks the species and becomes its favorite`() = runBlocking {
        val result = registrar.register("owl", "content://photos/1")

        result as CaptureRegistrar.RegisterResult.Registered
        assertTrue("first capture must trigger the reveal", result.isFirst)
        val entry = store.entries.getValue("owl")
        assertEquals(1_000L, entry.caughtAt)
        assertEquals(result.captureId, entry.favoriteCaptureId)
        assertEquals(thumbnailRelativePath(result.captureId), store.renderedThumbPath("owl"))
    }

    @Test
    fun `a repeat capture appends without a second unlock and leaves caughtAt alone`() =
        runBlocking {
            registrar.register("owl", "content://photos/1")
            clock = 9_000L
            val second = registrar.register("owl", "content://photos/2")

            second as CaptureRegistrar.RegisterResult.Registered
            assertFalse("only firsts get ceremony (M09)", second.isFirst)
            assertEquals(1_000L, store.entries.getValue("owl").caughtAt)
            assertEquals(2, store.capturesForSpecies("owl").size)
        }

    @Test
    fun `a photo that cannot be thumbnailed writes nothing at all`() = runBlocking {
        photos.thumbnailWorks = false

        val result = registrar.register("owl", "content://photos/broken")

        assertTrue(result is CaptureRegistrar.RegisterResult.ThumbnailFailed)
        assertTrue("no capture row may exist without its thumbnail (M11)", store.captures.isEmpty())
        assertTrue("the species must not read as caught", store.entries.isEmpty())
        assertEquals(
            "the grant taken for the failed attempt is handed back",
            listOf("content://photos/broken"),
            photos.released,
        )
    }

    @Test
    fun `registration proceeds when the provider refuses a persistable grant`() = runBlocking {
        photos.grantPersists = false

        val result = registrar.register("owl", "content://picker/1")

        assertTrue(result is CaptureRegistrar.RegisterResult.Registered)
        assertEquals(1, store.captures.size)
        assertTrue("nothing is released — nothing was taken", photos.released.isEmpty())
    }

    @Test
    fun `EXIF supplies takenAt and location when present, registration time when not`() =
        runBlocking {
            photos.exif = ExifFacts(takenAt = 42L, lat = 44.0, lng = -121.3)
            val withExif = registrar.register("owl", "content://photos/1")
                as CaptureRegistrar.RegisterResult.Registered
            assertEquals(42L, store.captures.getValue(withExif.captureId).takenAt)
            assertEquals(44.0, store.captures.getValue(withExif.captureId).lat!!, 0.0001)

            photos.exif = ExifFacts.None
            clock = 7_777L
            val without = registrar.register("frog", "content://photos/2", locationLabel = "Pond")
                as CaptureRegistrar.RegisterResult.Registered
            assertEquals(7_777L, store.captures.getValue(without.captureId).takenAt)
            assertNull(store.captures.getValue(without.captureId).lat)
        }

    @Test
    fun `no local copy is written by default (D6), and one is when the setting is on`() =
        runBlocking {
            registrar.register("owl", "content://photos/1")
            assertEquals(0, photos.localCopiesWritten)

            val copying = CaptureRegistrar(
                store = store,
                photos = photos,
                newCaptureId = { "cap-copy" },
                now = { clock },
                keepLocalCopy = { true },
            )
            copying.register("frog", "content://photos/2")
            assertEquals(1, photos.localCopiesWritten)
            assertEquals(
                localCopyRelativePath("cap-copy"),
                store.captures.getValue("cap-copy").localCopyPath,
            )
        }

    // -- Deletion (S07) ------------------------------------------------------

    @Test
    fun `deleting the last capture reverts the species to uncaught and frees everything`() =
        runBlocking {
            val only = (registrar.register("owl", "content://photos/1")
                as CaptureRegistrar.RegisterResult.Registered).captureId

            val plan = registrar.deleteCapture(only)!!

            assertTrue(plan.deleteEntry)
            assertTrue("the catch is gone only because its last photo was", store.entries.isEmpty())
            assertEquals(listOf(thumbnailRelativePath(only)), photos.deletedFiles)
            assertEquals(listOf("content://photos/1"), photos.released)
        }

    @Test
    fun `deleting one of several captures leaves the species caught`() = runBlocking {
        val first = (registrar.register("owl", "content://photos/1")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        registrar.register("owl", "content://photos/2")

        val plan = registrar.deleteCapture(first)!!

        assertFalse(plan.deleteEntry)
        assertNotNull("losing a photo never loses the catch", store.entries["owl"])
        assertEquals(1, store.capturesForSpecies("owl").size)
    }

    @Test
    fun `deleting the favorite nulls the column instead of dangling it`() = runBlocking {
        val first = (registrar.register("owl", "content://photos/1")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        clock = 2_000L
        val second = (registrar.register("owl", "content://photos/2")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        registrar.setFavorite("owl", first)

        registrar.deleteCapture(first)

        assertNull(
            "entries.favoriteCaptureId has no FK (3.4) — it dangles unless we null it",
            store.entries.getValue("owl").favoriteCaptureId,
        )
        assertEquals(
            "the grid falls back to the earliest remaining capture",
            thumbnailRelativePath(second),
            store.renderedThumbPath("owl"),
        )
    }

    @Test
    fun `deleting a non-favorite leaves the favorite pointing where it did`() = runBlocking {
        val first = (registrar.register("owl", "content://photos/1")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        val second = (registrar.register("owl", "content://photos/2")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        registrar.setFavorite("owl", second)

        registrar.deleteCapture(first)

        assertEquals(second, store.entries.getValue("owl").favoriteCaptureId)
    }

    @Test
    fun `a grant shared with another capture is never released`() = runBlocking {
        // The same gallery photo registered against two species — legal, and the case that
        // breaks the "one grant per capture" assumption ARCHITECTURE.md 4.4 makes.
        val owl = (registrar.register("owl", "content://photos/shared")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        registrar.register("heron", "content://photos/shared")

        registrar.deleteCapture(owl)

        assertTrue(
            "releasing here would blank the heron's photo too",
            photos.released.isEmpty(),
        )
        assertEquals(
            "the deleted capture's own thumbnail still goes",
            listOf(thumbnailRelativePath(owl)),
            photos.deletedFiles,
        )
    }

    @Test
    fun `deleting an unknown capture is a no-op, not a crash`() = runBlocking {
        assertNull(registrar.deleteCapture("nope"))
    }

    // -- Re-link (4.2) -------------------------------------------------------

    @Test
    fun `re-linking swaps the reference and thumbnail but keeps the capture and the catch`() =
        runBlocking {
            val id = (registrar.register("owl", "content://photos/old")
                as CaptureRegistrar.RegisterResult.Registered).captureId
            val caughtAt = store.entries.getValue("owl").caughtAt

            assertTrue(registrar.relink(id, "content://photos/new"))

            val capture = store.captures.getValue(id)
            assertEquals("content://photos/new", capture.photoUri)
            assertEquals(thumbnailRelativePath(id), capture.thumbPath)
            assertEquals(1_000L, capture.createdAt)
            assertEquals(caughtAt, store.entries.getValue("owl").caughtAt)
            assertEquals(listOf("content://photos/old"), photos.released)
        }

    @Test
    fun `re-linking fills a place the sighting never had (D67)`() = runBlocking {
        photos.exif = ExifFacts.None
        val r = registrar.register("owl", "content://photos/old", locationLabel = null)
        // The door refuses a placeless registration now, so seed the row the old way.
        assertEquals(CaptureRegistrar.RegisterResult.PlaceMissing, r)
        val placed = registrar.register("owl", "content://photos/old", locationLabel = "Typed")
            as CaptureRegistrar.RegisterResult.Registered
        store.captures[placed.captureId] = store.captures.getValue(placed.captureId).copy(locationLabel = null)

        photos.exif = ExifFacts(lat = 38.0, lng = -122.8)
        registrar.relink(placed.captureId, "content://photos/new")

        val row = store.captures.getValue(placed.captureId)
        assertEquals(38.0, row.lat!!, 0.0001)
        assertEquals("content://photos/new", row.photoUri)
    }

    @Test
    fun `re-linking does not release a grant another capture still needs`() = runBlocking {
        val owl = (registrar.register("owl", "content://photos/shared")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        registrar.register("heron", "content://photos/shared")

        registrar.relink(owl, "content://photos/new")

        assertTrue(photos.released.isEmpty())
    }

    @Test
    fun `a re-link whose thumbnail fails leaves the old reference untouched`() = runBlocking {
        val id = (registrar.register("owl", "content://photos/old")
            as CaptureRegistrar.RegisterResult.Registered).captureId
        photos.thumbnailWorks = false

        assertFalse(registrar.relink(id, "content://photos/new"))

        assertEquals("content://photos/old", store.captures.getValue(id).photoUri)
        assertFalse(
            "the reference that still works must keep its grant",
            photos.released.contains("content://photos/old"),
        )
        assertEquals(
            "the grant taken for the abandoned attempt is handed back",
            listOf("content://photos/new"),
            photos.released,
        )
    }

    // -- Grants (4.4) --------------------------------------------------------

    // -- Unlinking (D61) ---------------------------------------------------------

    @Test
    fun `unlinking drops the photo and keeps the sighting and the catch`() = runBlocking {
        photos.exif = ExifFacts(takenAt = 42L, lat = 44.0, lng = -121.3)
        val r = registrar.register("owl", "content://photos/1", note = "on the fence post")
            as CaptureRegistrar.RegisterResult.Registered

        val plan = registrar.unlinkPhoto(r.captureId)!!

        val row = store.captures.getValue(r.captureId)
        assertNull(row.photoUri)
        assertNull(row.thumbPath)
        assertNull(row.localCopyPath)
        assertEquals("the sighting's moment survives", 42L, row.takenAt)
        assertEquals("and its place", 44.0, row.lat!!, 0.0001)
        assertEquals("and its note", "on the fence post", row.note)
        assertTrue("the species stays caught", store.entries.containsKey("owl"))
        assertEquals(listOf(thumbnailRelativePath(r.captureId)), photos.deletedFiles)
        assertEquals(listOf("content://photos/1"), photos.released)
        assertFalse(plan.isNoOp)
    }

    @Test
    fun `unlinking a shared photo keeps the other capture's grant`() = runBlocking {
        val a = registrar.register("owl", "content://photos/shared")
            as CaptureRegistrar.RegisterResult.Registered
        registrar.register("frog", "content://photos/shared")
        photos.released.clear()

        registrar.unlinkPhoto(a.captureId)

        assertEquals(emptyList<String>(), photos.released)
        assertEquals(1, store.captures.values.count { it.photoUri == "content://photos/shared" })
    }

    @Test
    fun `unlinking the favourite lets the tile fall through to another photo`() = runBlocking {
        val first = registrar.register("owl", "content://photos/1")
            as CaptureRegistrar.RegisterResult.Registered
        clock = 2_000L
        val second = registrar.register("owl", "content://photos/2")
            as CaptureRegistrar.RegisterResult.Registered
        assertEquals(thumbnailRelativePath(first.captureId), store.renderedThumbPath("owl"))

        registrar.unlinkPhoto(first.captureId)

        assertEquals(
            "the DAO's COALESCE skips the photoless favourite",
            thumbnailRelativePath(second.captureId),
            store.renderedThumbPath("owl"),
        )
    }

    @Test
    fun `unlinking twice, or an unknown capture, is harmless`() = runBlocking {
        val r = registrar.register("owl", "content://photos/1")
            as CaptureRegistrar.RegisterResult.Registered
        registrar.unlinkPhoto(r.captureId)
        photos.deletedFiles.clear()
        photos.released.clear()

        assertTrue(registrar.unlinkPhoto(r.captureId)!!.isNoOp)
        assertEquals(emptyList<String>(), photos.deletedFiles)
        assertEquals(emptyList<String>(), photos.released)
        assertNull(registrar.unlinkPhoto("nope"))
    }

    @Test
    fun `grant pressure is reported against the 5000 cap`() {
        assertEquals(GrantPressure.FINE, grantPressure(0))
        assertEquals(GrantPressure.FINE, grantPressure(PERSISTED_GRANT_WARN_AT - 1))
        assertEquals(GrantPressure.NEAR_CAP, grantPressure(PERSISTED_GRANT_WARN_AT))
        assertEquals(GrantPressure.AT_CAP, grantPressure(PERSISTED_GRANT_CAP))
    }
}
