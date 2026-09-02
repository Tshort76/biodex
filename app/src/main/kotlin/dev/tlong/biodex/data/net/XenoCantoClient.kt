package dev.tlong.biodex.data.net

import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject

/**
 * Xeno-canto v3, keyed by the GBIF scientific name (DESIGN.md D10).
 *
 * **There is no API key today.** Since October 2025 the v3 API requires one per account
 * (ARCHITECTURE.md 5.4) and the user chose to skip bird calls for now, so
 * `BuildConfig.XC_API_KEY` is the empty string and this client answers [LookupResult.NotFound]
 * without making a request. That is the honest answer, and the confirm card renders it as the
 * ordinary "no call found" row rather than an error (M18). The client is built as if the key
 * existed: dropping a key into `local.properties` turns calls on with no code change.
 */
class XenoCantoClient(
    private val fetcher: JsonFetcher,
    private val apiKey: String,
) {

    suspend fun bestCall(scientificName: String?): LookupResult<CallFacts> {
        val name = scientificName?.trim().orEmpty()
        if (name.isEmpty()) return LookupResult.NotFound
        if (apiKey.isBlank()) return LookupResult.NotFound

        return when (val response = fetcher.get(recordingsUrl(name, apiKey))) {
            is FetchResult.Body -> parseBestRecording(response.text)
                ?.let { LookupResult.Found(it) }
                ?: LookupResult.NotFound

            FetchResult.NotFound -> LookupResult.NotFound
            is FetchResult.Failed -> LookupResult.Failed(response.reason)
        }
    }
}

data class CallFacts(val url: String, val attribution: String)

internal fun recordingsUrl(scientificName: String, apiKey: String): String {
    val query = URLEncoder.encode("sp:\"$scientificName\"", "UTF-8")
    return "https://xeno-canto.org/api/3/recordings?query=$query&key=" +
        URLEncoder.encode(apiKey, "UTF-8")
}

/** Quality A beats B beats the rest, exactly as the build-time pipeline ranks them. */
private val QUALITY_ORDER = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3, "E" to 4)

internal fun parseBestRecording(body: String): CallFacts? {
    val root = body.asJsonObject() ?: return null
    // A key problem answers 401 with `{"error": …}` rather than an empty recordings list.
    if (root.string("error") != null) return null
    val recordings = root.array("recordings").orEmpty().mapNotNull { it as? JsonObject }
    val best = recordings.minByOrNull { QUALITY_ORDER[it.string("q")] ?: 5 } ?: return null
    val url = best.string("file") ?: best.string("url") ?: return null
    val attribution = "Xeno-canto XC${best.string("id") ?: "?"} · " +
        (best.string("lic") ?: "see Xeno-canto") + " · " +
        (best.string("rec") ?: "unknown")
    return CallFacts(url, attribution)
}
