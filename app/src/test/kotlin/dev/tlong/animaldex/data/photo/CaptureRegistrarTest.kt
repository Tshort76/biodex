package dev.tlong.animaldex.data.photo

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
            val without = registrar.register("frog", "content://photos/2")
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

    @Test
    fun `grant pressure is reported against the 5000 cap`() {
        assertEquals(GrantPressure.FINE, grantPressure(0))
        assertEquals(GrantPressure.FINE, grantPressure(PERSISTED_GRANT_WARN_AT - 1))
        assertEquals(GrantPressure.NEAR_CAP, grantPressure(PERSISTED_GRANT_WARN_AT))
        assertEquals(GrantPressure.AT_CAP, grantPressure(PERSISTED_GRANT_CAP))
    }
}
