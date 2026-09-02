package dev.tlong.animaldex.ui.settings

import dev.tlong.animaldex.data.backup.ImportReport
import dev.tlong.animaldex.data.backup.PhotoReport
import dev.tlong.animaldex.data.photo.GrantPressure
import dev.tlong.animaldex.media.CacheSizes
import dev.tlong.animaldex.media.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user is told. The export sentence is the one that matters: S01 is the only
 * protection against losing the phone, so an archive missing three photos must say so in
 * the same breath as it says "saved".
 */
class SettingsStateTest {

    @Test
    fun `a complete export says so plainly`() {
        val line = exportSummary(
            "animaldex-backup-2026-09-01-0941.zip",
            PhotoReport(captures = 12, fullSizeIncluded = 12, thumbnailsIncluded = 12),
        )

        assertTrue(line.contains("Every photo you still have is in the archive"))
        assertTrue(line.contains("12 full-size photos"))
    }

    @Test
    fun `an incomplete export names the count and both reasons`() {
        val line = exportSummary(
            "backup.zip",
            PhotoReport(
                captures = 12,
                fullSizeIncluded = 9,
                missingRevoked = 2,
                missingOffline = 1,
                thumbnailsIncluded = 12,
            ),
        )

        assertTrue(line.contains("3 full-size photos are missing"))
        // The two reasons ask for different things from the user, so they are never merged.
        assertTrue(line.contains("no longer in your gallery and can never be exported"))
        assertTrue(line.contains("exporting again while online"))
        // And the catch itself is safe, which is the part that stops a false alarm.
        assertTrue(line.contains("thumbnails and every detail of the catch are still"))
    }

    @Test
    fun `an unreadable photo is reported as its own case`() {
        val line = exportSummary(
            "backup.zip",
            PhotoReport(captures = 1, fullSizeIncluded = 0, missingUnreadable = 1),
        )

        assertTrue(line.contains("1 full-size photo is missing"))
        assertTrue(line.contains("could not be read"))
    }

    @Test
    fun `an import says what it added and what it left alone`() {
        val line = importSummary(
            ImportReport(
                speciesAdded = 2,
                entriesAdded = 5,
                capturesAdded = 7,
                capturesAlreadyPresent = 3,
                capturesWithoutSpecies = 1,
                photosRestored = 6,
            ),
        )

        assertTrue(line.contains("Restored 7 captures"))
        assertTrue(line.contains("2 of your own species"))
        assertTrue(line.contains("3 were already here and were left alone"))
        assertTrue(line.contains("catalogue does not have their species"))
    }

    @Test
    fun `the grant line warns only when the cap is in sight`() {
        assertTrue(grantLine(12, GrantPressure.FINE).startsWith("12 of 5000"))
        assertTrue(grantLine(4600, GrantPressure.NEAR_CAP).contains("approaching"))
        assertTrue(grantLine(5000, GrantPressure.AT_CAP).contains("Delete some captures"))
    }

    @Test
    fun `cache sizes are reported per cache`() {
        val line = cacheLine(CacheSizes(imageBytes = 12L * 1024 * 1024, audioBytes = 0, httpBytes = 2048))
        assertEquals("Images 12.0 MB · Call audio 0 B · Lookups 2.0 KB", line)
    }

    @Test
    fun `byte formatting steps through the units`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.50 GB", formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}
