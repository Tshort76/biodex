package dev.tlong.animaldex.data.photo

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * What a photo's EXIF can tell us (ARCHITECTURE.md 4.1, M13). Location is nullable and its
 * absence is **ordinary**, not an error: the Android 13+ system picker redacts GPS from the
 * URIs it returns (risk R3), so on a modern phone this will almost always be null.
 */
data class ExifFacts(
    val takenAt: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
) {
    companion object {
        val None = ExifFacts()
    }
}

/**
 * EXIF `DateTimeOriginal` is `yyyy:MM:dd HH:mm:ss` with **no time zone** — it is local wall
 * time at the camera. Parsing it in the device's current zone is the only sane reading, and
 * it is what every gallery app does.
 *
 * Returns null for absent, blank, or unparseable values; the caller falls back to
 * registration time rather than treating this as a failure.
 */
fun parseExifDateTime(
    value: String?,
    timeZone: TimeZone = TimeZone.getDefault(),
): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    // "0000:00:00 00:00:00" is what some cameras write for "unset".
    if (text.startsWith("0000")) return null
    val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
        this.timeZone = timeZone
        isLenient = false
    }
    return runCatching { format.parse(text)?.time }.getOrNull()
}

/**
 * `takenAt` per M13: the EXIF timestamp when there is one, registration time otherwise. Kept
 * separate from parsing so the fallback is itself a tested rule rather than an `?:` buried in
 * the Android shell.
 */
fun takenAtOrFallback(facts: ExifFacts, registrationTime: Long): Long =
    facts.takenAt ?: registrationTime
