package dev.tlong.animaldex.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** M13: the EXIF timestamp when there is one, registration time otherwise — never an error. */
class ExifFactsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `DateTimeOriginal parses as local wall time`() {
        // 2026-08-30 19:42:00 UTC
        assertEquals(1_788_118_920_000L, parseExifDateTime("2026:08:30 19:42:00", utc))
    }

    @Test
    fun `the same instant reads differently in another zone, which is the point`() {
        val ny = parseExifDateTime("2026:08:30 19:42:00", TimeZone.getTimeZone("America/New_York"))
        assertEquals(1_788_118_920_000L + 4 * 3_600_000L, ny)
    }

    @Test
    fun `absent, blank, zeroed and malformed values are all simply null`() {
        assertNull(parseExifDateTime(null, utc))
        assertNull(parseExifDateTime("   ", utc))
        assertNull(parseExifDateTime("0000:00:00 00:00:00", utc))
        assertNull(parseExifDateTime("2026-08-30T19:42:00Z", utc))
        assertNull(parseExifDateTime("not a date", utc))
    }

    @Test
    fun `takenAt falls back to registration time when EXIF has no date`() {
        assertEquals(5_000L, takenAtOrFallback(ExifFacts.None, registrationTime = 5_000L))
        assertEquals(9L, takenAtOrFallback(ExifFacts(takenAt = 9L), registrationTime = 5_000L))
    }

    @Test
    fun `a missing location is ordinary — the picker redacts GPS (R3)`() {
        val facts = ExifFacts(takenAt = 1L)
        assertNull(facts.lat)
        assertNull(facts.lng)
        assertEquals(1L, takenAtOrFallback(facts, registrationTime = 2L))
    }
}
