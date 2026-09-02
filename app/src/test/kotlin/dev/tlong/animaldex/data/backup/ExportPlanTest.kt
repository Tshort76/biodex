package dev.tlong.animaldex.data.backup

import dev.tlong.animaldex.data.photo.PhotoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S01's completeness rules. The archive is the only protection against losing the phone, and
 * the design leans on it precisely because photos are referenced rather than copied — so the
 * two properties under test here are (a) every photo that still resolves gets into the ZIP
 * at full size, and (b) the manifest never claims one that did not.
 */
class ExportPlanTest {

    private val present = setOf(
        "thumbnails/a.jpg",
        "thumbnails/b.jpg",
        "thumbnails/c.jpg",
        "thumbnails/d.jpg",
        "photos/d.jpg",
    )

    private fun exists(path: String) = path in present

    @Test
    fun `a live gallery reference is exported at full size`() {
        val c = capture("a")
        val items = planExport(listOf(c), mapOf("a" to PhotoRef.Available(c.photoUri)), ::exists)

        assertEquals(PhotoDisposition.INCLUDED, items.single().disposition)
        assertEquals("photos/a.jpg", items.single().photoEntry)
        assertEquals("thumbnails/a.jpg", items.single().thumbnailEntry)
    }

    @Test
    fun `a local copy is exported without touching the gallery`() {
        val c = capture("d", localCopyPath = "photos/d.jpg")
        val items = planExport(listOf(c), mapOf("d" to PhotoRef.LocalCopy("photos/d.jpg")), ::exists)

        assertEquals(PhotoDisposition.INCLUDED, items.single().disposition)
        assertEquals(PhotoSource.Owned("photos/d.jpg"), items.single().photoSource)
    }

    @Test
    fun `the two broken states are reported apart, and keep their thumbnails`() {
        val items = planExport(
            listOf(capture("b"), capture("c")),
            mapOf("b" to PhotoRef.Revoked, "c" to PhotoRef.Unavailable),
            ::exists,
        )

        assertEquals(PhotoDisposition.MISSING_REVOKED, items[0].disposition)
        assertEquals(PhotoDisposition.MISSING_OFFLINE, items[1].disposition)
        assertNull(items[0].photoEntry)
        assertNull(items[1].photoEntry)
        // The catch survives the photo: the thumbnail and every detail still export.
        assertEquals("thumbnails/b.jpg", items[0].thumbnailEntry)
        assertEquals("thumbnails/c.jpg", items[1].thumbnailEntry)
    }

    @Test
    fun `a local copy whose file is gone is unreadable, not revoked`() {
        val c = capture("z", localCopyPath = "photos/z.jpg")
        val items = planExport(listOf(c), mapOf("z" to PhotoRef.LocalCopy("photos/z.jpg")), ::exists)

        assertEquals(PhotoDisposition.MISSING_UNREADABLE, items.single().disposition)
        assertNull(items.single().photoSource)
    }

    @Test
    fun `the manifest never names a photo the archive does not hold`() {
        val c = capture("a")
        val items = planExport(listOf(c), mapOf("a" to PhotoRef.Available(c.photoUri)), ::exists)

        // The plan said INCLUDED; the write failed, so only the thumbnail landed.
        val manifest = buildManifest(
            exportedAt = 5L,
            regionId = "pacific",
            species = emptyList(),
            entries = emptyList(),
            items = items,
            writtenEntries = setOf("thumbnails/a.jpg"),
        )

        val entry = manifest.captures.single()
        assertNull(entry.photoEntry)
        assertEquals("thumbnails/a.jpg", entry.thumbEntry)
        assertEquals(PhotoDisposition.MISSING_UNREADABLE.name, entry.photoStatus)
        assertEquals(0, manifest.photoReport.fullSizeIncluded)
        assertEquals(1, manifest.photoReport.missingUnreadable)
        assertTrue(!manifest.photoReport.complete)
    }

    @Test
    fun `a report with nothing missing is complete`() {
        val c = capture("a")
        val items = planExport(listOf(c), mapOf("a" to PhotoRef.Available(c.photoUri)), ::exists)
        val manifest = buildManifest(
            exportedAt = 5L,
            regionId = "pacific",
            species = emptyList(),
            entries = emptyList(),
            items = items,
            writtenEntries = setOf("thumbnails/a.jpg", "photos/a.jpg"),
        )

        assertTrue(manifest.photoReport.complete)
        assertEquals(1, manifest.photoReport.fullSizeIncluded)
        assertEquals(1, manifest.photoReport.thumbnailsIncluded)
    }

    @Test
    fun `a capture whose thumbnail file is missing still exports its record`() {
        val items = planExport(
            listOf(capture("nothumb")),
            mapOf("nothumb" to PhotoRef.Revoked),
            ::exists,
        )

        assertNull(items.single().thumbnailSource)
        val manifest = buildManifest(0L, "pacific", emptyList(), emptyList(), items, emptySet())
        assertEquals(1, manifest.captures.size)
        assertNull(manifest.captures.single().thumbEntry)
    }
}
