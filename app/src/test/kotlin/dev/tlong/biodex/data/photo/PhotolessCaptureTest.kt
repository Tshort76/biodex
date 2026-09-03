package dev.tlong.biodex.data.photo

import dev.tlong.biodex.data.backup.PhotoDisposition
import dev.tlong.biodex.data.backup.PhotoReport
import dev.tlong.biodex.data.backup.buildManifest
import dev.tlong.biodex.data.backup.planExport
import dev.tlong.biodex.domain.Capture
import dev.tlong.biodex.ui.photoviewer.availabilityFor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M41's capture with no photograph, across the four places a null would otherwise be read as a
 * loss: the grant, the viewer, the export report and the re-link offer.
 *
 * Each of these is a case where the *existing* code would have done something defensible and
 * wrong. A null URI would have classified as `Revoked` and offered a re-link for a photo that
 * never existed; the export would have counted it missing and told the user their backup was
 * incomplete; the deletion path would have released a grant it never took.
 */
class PhotolessCaptureTest {

    private fun photoless(id: String = "p1", speciesId: String = "salal") = Capture(
        id = id,
        speciesId = speciesId,
        photoUri = null,
        thumbPath = null,
        takenAt = 100L,
        createdAt = 100L,
    )

    private fun photographed(id: String = "a1", speciesId: String = "owl") = Capture(
        id = id,
        speciesId = speciesId,
        photoUri = "content://media/1",
        thumbPath = thumbnailRelativePath(id),
        takenAt = 100L,
        createdAt = 100L,
    )

    // -----------------------------------------------------------------------
    // Resolution.
    // -----------------------------------------------------------------------

    @Test
    fun `a null URI resolves to None without probing anything`() {
        var probed = false

        val ref = resolvePhotoRef(null, null) { probed = true; SecurityException() }

        assertEquals(PhotoRef.None, ref)
        assertFalse("there is nothing to probe", probed)
    }

    @Test
    fun `None is never confused with Revoked`() {
        assertFalse(PhotoRef.None.isRelinkable)
        assertTrue(PhotoRef.Revoked.isRelinkable)
        assertFalse(PhotoRef.None.isFullSizeShowable)
    }

    // -----------------------------------------------------------------------
    // The viewer. It should never open on such a capture at all; what is pinned
    // here is that even if it did, it makes no offer it cannot honour.
    // -----------------------------------------------------------------------

    @Test
    fun `a photoless capture is never offered a re-link`() {
        val availability = availabilityFor(PhotoRef.None)

        assertFalse(availability.offerRelink)
        assertNull("no banner: nothing has gone wrong", availability.bannerText)
        assertFalse(availability.showFullSize)
        // The contrast that makes the point.
        assertTrue(availabilityFor(PhotoRef.Revoked).offerRelink)
    }

    // -----------------------------------------------------------------------
    // The grant. A capture that never took one must never release one.
    // -----------------------------------------------------------------------

    @Test
    fun `deleting a photoless capture releases no grant`() {
        val capture = photoless()

        val plan = planCaptureDeletion(
            capture = capture,
            speciesCaptures = listOf(capture),
            favoriteCaptureId = capture.id,
            uriReferenceCount = 0,
        )

        assertNull(plan.releaseUri)
        assertEquals("and there are no files to delete either", emptyList<String>(), plan.filesToDelete)
        assertTrue("it was still the last capture, so the species is un-caught", plan.deleteEntry)
    }

    @Test
    fun `registering without a photo takes no grant, reads no EXIF and writes no thumbnail`() =
        runBlocking {
            val store = FakeCaptureStore()
            val photos = FakePhotoGateway()
            val registrar = CaptureRegistrar(store, photos, newCaptureId = { "cap-1" }, now = { 42L })

            val result = registrar.register(speciesId = "salal", photoUri = null)

            assertTrue(result is CaptureRegistrar.RegisterResult.Registered)
            assertEquals(emptyList<String>(), photos.persisted)
            assertEquals(emptyList<String>(), photos.writtenThumbnails)
            val capture = store.captures.getValue("cap-1")
            assertNull(capture.photoUri)
            assertNull(capture.thumbPath)
            // Everything else about the catch is still recorded: the date is registration
            // time, which is the honest answer when no EXIF was read.
            assertEquals(42L, capture.takenAt)
            assertTrue("it still unlocks the species", store.entries.containsKey("salal"))
        }

    @Test
    fun `a second photoless capture is a repeat catch, not a first`() = runBlocking {
        val store = FakeCaptureStore()
        val registrar = CaptureRegistrar(store, FakePhotoGateway(), now = { 42L })

        val first = registrar.register("salal", null) as CaptureRegistrar.RegisterResult.Registered
        val again = registrar.register("salal", null) as CaptureRegistrar.RegisterResult.Registered

        assertTrue(first.isFirst)
        assertFalse("seen again, here, on this date", again.isFirst)
    }

    // -----------------------------------------------------------------------
    // Export. The rule that matters: an archive of photoless plants is
    // *complete*, and must not tell the user photos were left behind.
    // -----------------------------------------------------------------------

    @Test
    fun `a mix of photoless plants and photographed animals exports as complete`() {
        val plant = photoless("p1")
        val animal = photographed("a1")
        val present = setOf(animal.thumbPath!!)

        val items = planExport(
            captures = listOf(plant, animal),
            refs = mapOf(
                "p1" to PhotoRef.None,
                "a1" to PhotoRef.Available(animal.photoUri!!),
            ),
            ownedFileExists = { it in present },
        )
        val manifest = buildManifest(
            exportedAt = 1L,
            regionId = "pacific",
            species = emptyList(),
            entries = emptyList(),
            items = items,
            writtenEntries = setOf("thumbnails/a1.jpg", "photos/a1.jpg"),
        )

        val report = manifest.photoReport
        assertTrue("nothing was lost, so the archive is complete", report.complete)
        assertEquals(0, report.missingTotal)
        assertEquals(1, report.neverHadPhoto)
        assertEquals(1, report.fullSizeIncluded)
        assertEquals(
            PhotoDisposition.NONE.name,
            manifest.captures.single { it.id == "p1" }.photoStatus,
        )
        assertNull(manifest.captures.single { it.id == "p1" }.photoUri)
    }

    @Test
    fun `NONE is not downgraded to unreadable when no bytes were written`() {
        // The "we planned it and it did not land" downgrade is about bytes that went away
        // mid-copy. There were never any bytes here.
        val items = planExport(
            captures = listOf(photoless("p1")),
            refs = mapOf("p1" to PhotoRef.None),
            ownedFileExists = { false },
        )

        assertEquals(PhotoDisposition.NONE, items.single().disposition)
        assertNull(items.single().photoEntry)
        assertNull(items.single().thumbnailEntry)
    }

    @Test
    fun `a photoless capture is not counted among the missing`() {
        val report = PhotoReport(captures = 3, neverHadPhoto = 2, missingRevoked = 0)

        assertEquals(0, report.missingTotal)
        assertTrue(report.complete)
    }
}
