package dev.tlong.biodex.data.identify

import dev.tlong.biodex.data.net.LookupResult
import dev.tlong.biodex.domain.Kingdom

/**
 * The one interface identification adds (DESIGN-identification §6.1, D21).
 *
 * Identification is a **query producer**, not a new pipeline: everything downstream of the
 * Register screen already keys off a name, and this returns names. So the outcome is the
 * network layer's existing [LookupResult] rather than a type of its own — and its three-way
 * discipline is load-bearing here (M38). "The service answered and recognised nothing in this
 * photo" is [LookupResult.NotFound], an ordinary answer about the world, rendered calmly.
 * [LookupResult.Failed] means the app could not ask — no connection, a quota, a rejected key —
 * and is the only outcome styled as an error.
 */
interface SpeciesIdentifier {

    /** Shown in the candidate panel's heading, never "BioDex thinks" (M34). */
    val providerName: String

    /** How the score this provider reports may be rendered (D22). */
    val scoreKind: ScoreKind

    suspend fun identify(image: UploadImage, kingdom: Kingdom): LookupResult<List<IdCandidate>>
}

/**
 * What kind of number came back, which decides how it may be shown (D22, M34).
 *
 * The distinction is the whole reason this is a type rather than a `Double`. A classifier's
 * score is a probability the user can weigh, so it renders as a percentage. A language model's
 * "confidence" is a token it emitted, so it may only ever render as an ordered list with no
 * number. Every provider in v3 is [CALIBRATED]; [SELF_REPORTED] exists so that a future
 * language-model provider cannot be added without confronting the difference.
 */
enum class ScoreKind { CALIBRATED, SELF_REPORTED }

/**
 * One raw suggestion, exactly as the service phrased it. Nothing here has been validated yet —
 * [CandidateResolver] is what turns these into something the app may display (M32).
 */
data class IdCandidate(
    /** The provider's own string, e.g. `Mahonia aquifolium`. Never trusted as a name. */
    val scientificName: String,
    val commonName: String? = null,
    /** Null for a [ScoreKind.SELF_REPORTED] provider, which reports no probability. */
    val score: Double? = null,
)

/**
 * The bytes that go on the wire (M36). The interface never sees a URI, so no implementation
 * can reach back to the original file and upload its EXIF: the only way in is a decoded,
 * re-encoded JPEG, and re-encoding is what strips the location the user's home is tagged with.
 */
data class UploadImage(
    val bytes: ByteArray,
    val fileName: String = "photo.jpg",
    val mimeType: String = "image/jpeg",
) {
    // A data class over a ByteArray gets reference equality for free, which is wrong in a
    // fixture comparison and silently so.
    override fun equals(other: Any?): Boolean =
        other is UploadImage &&
            bytes.contentEquals(other.bytes) &&
            fileName == other.fileName &&
            mimeType == other.mimeType

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + fileName.hashCode()) * 31 + mimeType.hashCode()
}

/**
 * Which provider, if any, will identify a given kingdom (D19, D21).
 *
 * v3 has one entry, `PLANT`, and **the absence of `ANIMAL` and `FUNGUS` is the mechanism that
 * hides the Identify button for them** (§5.1) — there is no separate "is this kingdom
 * identifiable" flag to keep in step with the registry. Adding an animal provider later is one
 * more entry here and nothing else (S14).
 */
class IdentifierRegistry(private val byKingdom: Map<Kingdom, SpeciesIdentifier>) {

    operator fun get(kingdom: Kingdom): SpeciesIdentifier? = byKingdom[kingdom]

    fun supports(kingdom: Kingdom): Boolean = byKingdom.containsKey(kingdom)
}
