package dev.tlong.biodex.data.identify

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** M37's cap, and the rollover that makes "this month" mean what the user thinks it means. */
class IdentificationBudgetTest {

    @Test
    fun `a count from this month is what has been spent`() {
        val stored = IdentificationCount(month = "2026-09", used = 12)

        assertEquals(12, identificationsUsed(stored, "2026-09"))
        assertEquals(88, identificationsRemaining(stored, "2026-09"))
    }

    @Test
    fun `a count from a previous month is spent, not carried`() {
        val stored = IdentificationCount(month = "2026-08", used = 100)

        assertEquals(0, identificationsUsed(stored, "2026-09"))
        assertEquals(100, identificationsRemaining(stored, "2026-09"))
    }

    @Test
    fun `a first run has no stored month and has spent nothing`() {
        assertEquals(0, identificationsUsed(IdentificationCount("", 0), "2026-09"))
    }

    @Test
    fun `the cap is a floor at zero rather than a negative remainder`() {
        val stored = IdentificationCount(month = "2026-09", used = 140)

        assertEquals(0, identificationsRemaining(stored, "2026-09", cap = 100))
    }

    @Test
    fun `a lowered cap takes effect against the month's existing count`() {
        val stored = IdentificationCount(month = "2026-09", used = 12)

        assertEquals(8, identificationsRemaining(stored, "2026-09", cap = 20))
    }

    @Test
    fun `the month key is the device's own calendar month, zero-padded`() {
        val utc = TimeZone.getTimeZone("UTC")

        // 2025-09-02T00:00:00Z
        assertEquals("2025-09", currentMonthKey(1_756_771_200_000L, utc))
        // 2026-01-15T00:00:00Z — January must not read as "2026-1".
        assertEquals("2026-01", currentMonthKey(1_768_435_200_000L, utc))
    }

    @Test
    fun `the sentences name the numbers the user is being held to`() {
        assertEquals("12 of 100 identifications used this month.", identificationCountLine(12))
        assertEquals("Identification paused: 100 of 100 this month", capReachedReason(100))
    }
}
