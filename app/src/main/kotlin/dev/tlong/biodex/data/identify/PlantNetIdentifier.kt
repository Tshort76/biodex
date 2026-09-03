package dev.tlong.biodex.data.identify

import dev.tlong.biodex.data.net.FetchResult
import dev.tlong.biodex.data.net.LookupResult
import dev.tlong.biodex.data.net.array
import dev.tlong.biodex.data.net.asJsonObject
import dev.tlong.biodex.data.net.obj
import dev.tlong.biodex.data.net.string
import dev.tlong.biodex.domain.Kingdom
import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pl@ntNet's v2 identify endpoint (§3.1, M31). Plants only — verified: the "Fungi" pages on
 * identify.plantnet.org are user-created observation groups, not a classifier, and the API is
 * documented for plants alone. That is why the registry has one entry (D19).
 *
 * The split follows the network layer's one rule (ARCHITECTURE.md 5.2): the socket is behind
 * [IdentifyTransport], and everything above it — the URL, the parse, the outcome mapping — is
 * ordinary Kotlin the JVM suite drives against captured payloads.
 *
 * **The key is a runtime value, never a build one** (M39, D24). This repository is public and
 * a `BuildConfig` key is one careless `git add` away from being in it, so the key arrives as a
 * lambda reading `AppSettings` on every call: pasting one in Settings has to work on the next
 * press, not the next process.
 */
class PlantNetIdentifier(
    private val transport: IdentifyTransport,
    private val apiKey: () -> String?,
) : SpeciesIdentifier {

    override val providerName: String = PROVIDER_NAME

    /** Pl@ntNet is a real classifier, so its score is a probability the user may weigh (D22). */
    override val scoreKind: ScoreKind = ScoreKind.CALIBRATED

    override suspend fun identify(
        image: UploadImage,
        kingdom: Kingdom,
    ): LookupResult<List<IdCandidate>> {
        // Defensive rather than expected: the registry is what decides who is called, so a
        // non-plant reaching here means the registry and this class disagree.
        if (kingdom != Kingdom.PLANT) {
            return LookupResult.Failed("$PROVIDER_NAME identifies plants only")
        }
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return LookupResult.Failed(NO_KEY_REASON)

        return when (val response = transport.post(identifyUrl(key), image)) {
            is FetchResult.Body -> parsePlantNetCandidates(response.text)
            // Pl@ntNet answers a photo it recognises nothing in with a 404 and a
            // `Species not found` body. That is the service answering, not a failure (M38).
            FetchResult.NotFound -> LookupResult.NotFound
            is FetchResult.Failed -> LookupResult.Failed(response.reason)
        }
    }

    companion object {
        const val PROVIDER_NAME = "Pl@ntNet"

        const val NO_KEY_REASON = "Add a Pl@ntNet key in Settings to identify plants"

        /** Where the user goes to get one (M39, R16); shown in Settings beside the field. */
        const val KEY_SIGNUP_URL = "https://my.plantnet.org/"
    }
}

/**
 * The transport seam. [JsonFetcher][dev.tlong.biodex.data.net.JsonFetcher] is GET-only and
 * stays that way — identification is the app's one multipart POST, and widening the shared
 * fetcher for it would put an upload path in front of every read.
 *
 * The signature takes [UploadImage] rather than a URI for the reason M36 turns on: nothing
 * below this line can reach the original file, so nothing below this line can leak its EXIF.
 */
fun interface IdentifyTransport {
    suspend fun post(url: String, image: UploadImage): FetchResult
}

/**
 * `project=all` is the world-wide flora rather than one regional checklist: the catalogue is
 * Pacific USA, but a user's photo of a garden escape should still come back named rather than
 * unrecognised, and it is GBIF and the catalogue match that decide what the app will show.
 *
 * `nb-results` is [MAX_RESULTS] because the resolver validates at most five candidates through
 * GBIF (§6.2) and a sixth would be fetched and thrown away.
 */
internal fun identifyUrl(apiKey: String): String =
    "https://my-api.plantnet.org/v2/identify/all" +
        "?include-related-images=false&no-reject=false&nb-results=$MAX_RESULTS&lang=en" +
        "&api-key=" + URLEncoder.encode(apiKey, "UTF-8")

/** §6.2: the resolver walks at most five candidates, so there is no point asking for more. */
const val MAX_RESULTS = 5

/**
 * The multipart field name Pl@ntNet's `images` parameter expects. Sent once — one photo, one
 * organ — because the Register screen attaches one photo (M07).
 */
const val IMAGE_PART_NAME = "images"

/**
 * The `organs` parameter tells the classifier what part of the plant is in frame. `auto` lets
 * the service decide, which is the honest answer for a photo the app never looked at: BioDex
 * has no way to know whether the user framed a leaf, a flower or the whole shrub, and guessing
 * would degrade the score it then shows as a probability.
 */
const val ORGAN_PART_NAME = "organs"
const val ORGAN_AUTO = "auto"

/**
 * `results[].species.scientificNameWithoutAuthor` and `score`, and nothing else.
 *
 * The name is taken **without** its author string on purpose. It is what GBIF's `match`
 * endpoint wants, and it is the shape the catalogue's own `scientificName` is in, so both
 * comparisons downstream are like-for-like.
 *
 * An answer with an empty `results` array is [LookupResult.NotFound], not a failure: the
 * service looked and recognised nothing, which is an ordinary thing for a photo of a bare
 * trunk or a blurred leaf to be (M38, §5.2 rule 8).
 */
internal fun parsePlantNetCandidates(body: String): LookupResult<List<IdCandidate>> {
    val root = body.asJsonObject()
        ?: return LookupResult.Failed("$PLANTNET could not be understood")
    val results = root.array("results")
        ?: return LookupResult.Failed("$PLANTNET returned no result list")
    val candidates = results.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val species = row.obj("species") ?: return@mapNotNull null
        val name = species.string("scientificNameWithoutAuthor") ?: return@mapNotNull null
        IdCandidate(
            scientificName = name,
            commonName = species.array("commonNames")
                ?.firstNotNullOfOrNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) },
            score = row.double("score"),
        )
    }
    return if (candidates.isEmpty()) LookupResult.NotFound else LookupResult.Found(candidates)
}

private const val PLANTNET = PlantNetIdentifier.PROVIDER_NAME

private fun JsonObject.double(key: String): Double? =
    runCatching { this[key]?.jsonPrimitive?.double }.getOrNull()
