package dev.tlong.biodex.data.photo

import android.content.Context
import android.location.Geocoder
import android.util.Log
import java.util.Locale

/**
 * D56. Turns a coordinate pair into a short place name — "Point Reyes Station, CA" — so a
 * sighting whose photo carried GPS reads as a place rather than as six decimals. Best effort
 * by contract: null on any failure, and the caller keeps the coordinates regardless.
 */
fun interface PlaceNamer {
    fun nameFor(lat: Double, lng: Double): String?

    companion object {
        /** No naming at all — the JVM suite's default, and the app's when nothing is wired. */
        val None = PlaceNamer { _, _ -> null }
    }
}

/**
 * The platform `Geocoder`, which reaches a network service and so may be slow or absent
 * (offline, or a device without a geocoding backend). [CaptureRegistrar] calls it on IO under
 * a timeout, and the answer is trimmed to locality and region: a street address on a wildlife
 * sighting is both noise and more than the user asked to store.
 */
class AndroidPlaceNamer(private val context: Context) : PlaceNamer {

    override fun nameFor(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION") // The synchronous form is the one usable off a callback.
            val address = Geocoder(context, Locale.getDefault())
                .getFromLocation(lat, lng, 1)
                ?.firstOrNull() ?: return null
            shortPlaceName(
                locality = address.locality ?: address.subAdminArea,
                region = address.adminArea,
                country = address.countryCode,
            )
        } catch (e: Exception) {
            Log.i(TAG, "No place name for $lat,$lng: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "PlaceNamer"
    }
}

/**
 * "Locality, Region" when both are known, whichever one is known otherwise, and the country
 * only when nothing finer came back. Null when the geocoder returned an address with none of
 * them, so the caller falls back to coordinates rather than storing an empty string.
 */
internal fun shortPlaceName(locality: String?, region: String?, country: String?): String? {
    val parts = listOfNotNull(locality?.trim()?.takeIf { it.isNotEmpty() },
        region?.trim()?.takeIf { it.isNotEmpty() })
    if (parts.isNotEmpty()) return parts.joinToString(", ")
    return country?.trim()?.takeIf { it.isNotEmpty() }
}
