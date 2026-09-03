package dev.tlong.biodex.data.identify

import java.util.Calendar
import java.util.TimeZone

/**
 * M37's hard monthly cap, as arithmetic over two stored values.
 *
 * The cap is a **hard stop, not a warning**: at 100 of 100 the Identify action is disabled with
 * the count in its reason, rather than staying live with a nag. 100 is twice the expected
 * volume (§2's ~50 a month) and comfortably under every free tier considered, so in ordinary
 * use it is invisible — it exists so that a stuck retry loop or a forgotten screen cannot spend
 * the user's quota, or their goodwill with the provider, while they are not looking.
 *
 * The month is the device's calendar month rather than a rolling 30 days, because "100 this
 * month" is a sentence the user can check against a calendar and a rolling window is not.
 */
data class IdentificationCount(
    /** `yyyy-MM` of the month the count belongs to. */
    val month: String,
    val used: Int,
)

const val DEFAULT_MONTHLY_IDENTIFICATION_CAP = 100

/**
 * The count as it applies *now*. A stored count from a previous month is spent, not carried:
 * the rollover happens on the first read in a new month rather than on a timer, so nothing has
 * to run while the app is closed.
 */
fun identificationsUsed(stored: IdentificationCount, currentMonth: String): Int =
    if (stored.month == currentMonth) stored.used else 0

fun identificationsRemaining(
    stored: IdentificationCount,
    currentMonth: String,
    cap: Int = DEFAULT_MONTHLY_IDENTIFICATION_CAP,
): Int = (cap - identificationsUsed(stored, currentMonth)).coerceAtLeast(0)

/** What Settings shows and what the disabled button repeats back (M37). */
fun identificationCountLine(used: Int, cap: Int = DEFAULT_MONTHLY_IDENTIFICATION_CAP): String =
    "$used of $cap identifications used this month."

fun capReachedReason(used: Int, cap: Int = DEFAULT_MONTHLY_IDENTIFICATION_CAP): String =
    "Identification paused: $used of $cap this month"

/**
 * `yyyy-MM` in the device's own zone. Deliberately not UTC: a cap the user is told resets "next
 * month" should turn over when their calendar does.
 */
fun currentMonthKey(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val calendar = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    return "$year-" + month.toString().padStart(2, '0')
}
