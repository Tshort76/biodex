package dev.tlong.biodex.data.net

import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.TaxClass
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GBIF is the spine of the lookup (DESIGN.md D10): it decides which species a name means.
 *
 * **Deviation from ARCHITECTURE.md 5.2, verified against the live API on 2026-09-01.**
 * `species/match` does not resolve common names *at all* — "Varied Thrush" and "sparrow" both
 * come back `{"matchType":"NONE"}`. The build-time pipeline never met this because the curator
 * supplies scientific names; the runtime user types a common name, which is the entire point of
 * M08. So the client is two-step: try `match` (which still wins when the user types a scientific
 * name, and carries GBIF's own confidence and alternatives), and on NONE fall back to
 * `species/search` over the backbone dataset with `qField=VERNACULAR`.
 *
 * **Slice 12 adds the second kingdom.** The vernacular search is scoped to one higher taxon or
 * it returns fungi and bacteria, so it is run against Animalia and then, unless the animal pass
 * produced an exact vernacular hit, against Plantae as well. Both of slice 12's phone names
 * need it: "Trailing Blackberry" and "Pacific Rhododendron" both return **zero** results under
 * Animalia (verified live 2026-09-02), and neither resolves through `species/match` either.
 */
class GbifClient(private val fetcher: JsonFetcher) {

    suspend fun match(name: String): LookupResult<GbifMatch> {
        val query = name.trim()
        if (query.isEmpty()) return LookupResult.NotFound

        val matched = when (val response = fetcher.get(matchUrl(query))) {
            is FetchResult.Body -> parseGbifMatch(response.text)
            FetchResult.NotFound -> null
            is FetchResult.Failed -> return LookupResult.Failed(response.reason)
        }
        if (matched != null) return LookupResult.Found(matched)

        val animals = when (val response = fetcher.get(vernacularSearchUrl(query))) {
            is FetchResult.Body -> parseGbifVernacularSearch(response.text, query)
            FetchResult.NotFound -> emptyList()
            // Only the first search decides whether the app could ask at all. A failure on the
            // second one degrades to "no candidates from that kingdom" (5.2's rule for every
            // source), which is why the plant pass below never returns Failed.
            is FetchResult.Failed -> return LookupResult.Failed(response.reason)
        }
        if (animals.any { it.matchKind == MatchKind.VERNACULAR_EXACT }) {
            return LookupResult.Found(GbifMatch(animals.first(), animals.drop(1)))
        }

        val plants = when (val response = fetcher.get(vernacularSearchUrl(query, PLANTAE_KEY))) {
            is FetchResult.Body -> parseGbifVernacularSearch(response.text, query)
            else -> emptyList()
        }
        val candidates = rankVernacularCandidates(animals + plants)
        return if (candidates.isEmpty()) {
            LookupResult.NotFound
        } else {
            LookupResult.Found(GbifMatch(candidates.first(), candidates.drop(1)))
        }
    }

    /**
     * GBIF's synonyms for one accepted usage, which is what the Duke's join needs (11.2, R15).
     * A species with no usage key, or a request that fails, degrades to an empty list: the
     * accepted binomial is still tried, and a miss is an ordinary state.
     */
    suspend fun synonyms(usageKey: Long?): List<String> {
        if (usageKey == null) return emptyList()
        return when (val response = fetcher.get(synonymsUrl(usageKey))) {
            is FetchResult.Body -> parseGbifSynonyms(response.text)
            else -> emptyList()
        }
    }
}

/** GBIF's own backbone taxonomy — the dataset `species/match` resolves against. */
private const val BACKBONE_DATASET = "d7dddbf4-2cf0-4f39-9b2a-bb099caae36c"

/** Animalia. Without it a vernacular search happily returns fungi and bacteria. */
private const val ANIMALIA_KEY = 1

/** Plantae, the second kingdom BioDex counts (D12). */
internal const val PLANTAE_KEY = 6

/** More than the card can show; the extras only widen the "other matches" list. */
internal const val GBIF_CANDIDATE_LIMIT = 6

/** GBIF returns plenty of trinomial synonyms; the Duke's key uses only the first two tokens. */
private const val GBIF_SYNONYM_LIMIT = 50

internal fun matchUrl(name: String): String =
    "https://api.gbif.org/v1/species/match?strict=false&verbose=true&name=" + name.urlEncoded()

internal fun vernacularSearchUrl(name: String, higherTaxonKey: Int = ANIMALIA_KEY): String =
    "https://api.gbif.org/v1/species/search?qField=VERNACULAR&rank=SPECIES&status=ACCEPTED" +
        "&datasetKey=$BACKBONE_DATASET&highertaxonKey=$higherTaxonKey" +
        "&limit=$GBIF_CANDIDATE_LIMIT&q=" + name.urlEncoded()

internal fun synonymsUrl(usageKey: Long): String =
    "https://api.gbif.org/v1/species/$usageKey/synonyms?limit=$GBIF_SYNONYM_LIMIT"

private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

private val json = Json { ignoreUnknownKeys = true }

/**
 * One species GBIF thinks the typed name might mean. [confidenceLabel] is deliberately honest
 * rather than reassuring: the pipeline caught GBIF resolving "Roosevelt Elk" to the European
 * red deer, which is exactly the mistake M19's confirmation card exists to catch.
 */
data class SpeciesCandidate(
    val scientificName: String,
    /** GBIF's English vernacular, when it has one; the card shows it beside the science. */
    val commonName: String? = null,
    /** GBIF's kingdom, which decides which extra fields the confirm card shows (M27). */
    val kingdom: Kingdom = Kingdom.ANIMAL,
    val taxClass: TaxClass,
    val usageKey: Long? = null,
    val rank: String? = null,
    val confidence: Int = 0,
    val matchKind: MatchKind,
    /** GBIF marks fossil taxa; nothing the user photographed this weekend is one. */
    val extinct: Boolean = false,
    /**
     * The conifer/broadleaf silhouette choice, the pipeline's one use of GBIF's plant class
     * (11.3 step 1). Null for everything that is not a tree.
     */
    val silhouetteResOverride: String? = null,
) {
    val confidenceLabel: String
        get() = when (matchKind) {
            MatchKind.EXACT -> "exact match"
            MatchKind.VERNACULAR_EXACT -> "exact name match"
            MatchKind.FUZZY -> "close match ($confidence%) — check this"
            MatchKind.HIGHER_RANK -> "matched a broader group — check this"
            MatchKind.VERNACULAR_OTHER -> "name appears in this species' common names"
        }
}

enum class MatchKind { EXACT, FUZZY, HIGHER_RANK, VERNACULAR_EXACT, VERNACULAR_OTHER }

data class GbifMatch(
    val best: SpeciesCandidate,
    val alternatives: List<SpeciesCandidate> = emptyList(),
) {
    val candidates: List<SpeciesCandidate> get() = listOf(best) + alternatives
}

/**
 * GBIF's backbone carries **no `class` for ray-finned fishes** — the Actinopterygii node is
 * gone, so Chinook Salmon comes back with `order: Salmoniformes` and nothing above it but
 * `phylum: Chordata`. Slice 2 found eight of its ten fish filed as invertebrates before this
 * rule existed; it is ported here verbatim from `tools/catalogue/build_catalogue.py`, because
 * a user-added fish hits exactly the same hole.
 */
internal fun taxClassFor(gbifClass: String?, phylum: String?): TaxClass {
    val mapped = gbifClass?.trim()?.lowercase()?.let(CLASS_MAP::get)
    if (mapped != null) return mapped
    if (gbifClass.isNullOrBlank() && phylum?.trim()?.lowercase() == "chordata") return TaxClass.FISH
    return TaxClass.OTHER_INVERTEBRATE
}

/**
 * M27's growth-form default, ported from the pipeline's step 1 (11.3): conifers to tree, ferns
 * to fern, everything else to herb.
 *
 * **Herb is the default on purpose, and R10 is why.** GBIF answers `Magnoliopsida` for an oak
 * and for a dandelion alike, and often answers nothing at all, so no automated rule can tell a
 * tree from a wildflower. Growth form is the user's pick on the card; this only decides which
 * chip is highlighted when it opens. The conifer signal is the one that is real, and it is
 * checked on both the class and the order because GBIF's plant classes are inconsistent.
 */
internal fun defaultPlantClass(gbifClass: String?, gbifOrder: String?): TaxClass {
    val klass = gbifClass?.trim()?.lowercase()
    val order = gbifOrder?.trim()?.lowercase()
    return when {
        klass in FERN_CLASSES -> TaxClass.FERN
        klass in CONIFER_CLASSES || order in CONIFER_ORDERS -> TaxClass.TREE
        else -> TaxClass.HERB
    }
}

/** Conifer or broadleaf, for a tree; null for every other growth form. */
internal fun plantSilhouetteFor(taxClass: TaxClass, gbifClass: String?, gbifOrder: String?): String? {
    if (taxClass != TaxClass.TREE) return null
    val conifer = gbifClass?.trim()?.lowercase() in CONIFER_CLASSES ||
        gbifOrder?.trim()?.lowercase() in CONIFER_ORDERS
    return if (conifer) "sil_tree_conifer" else "sil_tree_broadleaf"
}

/**
 * GBIF spells its kingdoms `Animalia` and `Plantae`; BioDex stores `animal` and `plant`, so the
 * two vocabularies are joined here rather than by widening `Kingdom.fromWireName`. Anything
 * else — Fungi, Chromista — falls back to animal, the same stance that enum already takes, and
 * cannot arrive from the vernacular search at all because it is scoped to these two.
 */
internal fun gbifKingdom(value: String?): Kingdom =
    if (value?.trim()?.lowercase() == "plantae") Kingdom.PLANT else Kingdom.ANIMAL

private val FERN_CLASSES =setOf("polypodiopsida", "pteridopsida", "filicopsida", "psilotopsida")

private val CONIFER_CLASSES = setOf("pinopsida", "coniferopsida", "ginkgoopsida", "cycadopsida")

private val CONIFER_ORDERS = setOf("pinales", "cupressales", "araucariales", "taxales")

private val CLASS_MAP = mapOf(
    "aves" to TaxClass.BIRD,
    "mammalia" to TaxClass.MAMMAL,
    "reptilia" to TaxClass.REPTILE,
    "squamata" to TaxClass.REPTILE,
    "testudines" to TaxClass.REPTILE,
    "amphibia" to TaxClass.AMPHIBIAN,
    "insecta" to TaxClass.INSECT,
    "actinopterygii" to TaxClass.FISH,
    "actinopteri" to TaxClass.FISH,
    "teleostei" to TaxClass.FISH,
    "chondrichthyes" to TaxClass.FISH,
    "elasmobranchii" to TaxClass.FISH,
)

/** Returns null when GBIF resolved nothing usable, which is the signal to try vernaculars. */
internal fun parseGbifMatch(body: String): GbifMatch? {
    val root = body.asJsonObject() ?: return null
    val best = root.toCandidate() ?: return null
    val alternatives = (root["alternatives"] as? JsonArray)
        .orEmpty()
        .mapNotNull { (it as? JsonObject)?.toCandidate() }
        // GBIF's alternatives are mostly synonyms of the accepted name; showing the same
        // species twice under two spellings would make the "other matches" link a lie.
        .filter { it.scientificName != best.scientificName }
        .distinctBy { it.scientificName }
        .take(GBIF_CANDIDATE_LIMIT - 1)
    return GbifMatch(best, alternatives)
}

private fun JsonObject.toCandidate(): SpeciesCandidate? {
    val matchType = string("matchType")?.uppercase() ?: "NONE"
    if (matchType == "NONE") return null
    // `species` is the accepted binomial; `canonicalName` is what was matched, which for a
    // synonym or a subspecies is not the name the entry should carry.
    val name = string("species") ?: string("canonicalName") ?: return null
    val (kingdom, taxClass, silhouette) = classify()
    return SpeciesCandidate(
        scientificName = name,
        kingdom = kingdom,
        taxClass = taxClass,
        silhouetteResOverride = silhouette,
        usageKey = long("speciesKey") ?: long("usageKey"),
        rank = string("rank"),
        confidence = int("confidence") ?: 0,
        matchKind = when (matchType) {
            "EXACT" -> MatchKind.EXACT
            "HIGHERRANK" -> MatchKind.HIGHER_RANK
            else -> MatchKind.FUZZY
        },
    )
}

/**
 * The kingdom is read **before** the class, because GBIF's plant classes mean nothing to the
 * animal class map: routing *Arbutus menziesii* (`Magnoliopsida`) through it would file a
 * madrone as an other-invertebrate, which is exactly the shape of the bug slice 2 hit with fish.
 */
private fun JsonObject.classify(): Triple<Kingdom, TaxClass, String?> {
    val kingdom = gbifKingdom(string("kingdom"))
    if (kingdom != Kingdom.PLANT) {
        return Triple(Kingdom.ANIMAL, taxClassFor(string("class"), string("phylum")), null)
    }
    val taxClass = defaultPlantClass(string("class"), string("order"))
    return Triple(kingdom, taxClass, plantSilhouetteFor(taxClass, string("class"), string("order")))
}

/**
 * The vernacular path. GBIF's own relevance puts "Coyote Snowfly" above *Canis latrans* for
 * "Coyote", so a species whose English vernacular *equals* the typed name is promoted to the
 * front. A query that matches nothing exactly keeps GBIF's order rather than inventing one.
 */
internal fun parseGbifVernacularSearch(body: String, query: String): List<SpeciesCandidate> {
    val results = (body.asJsonObject()?.get("results") as? JsonArray).orEmpty()
    val wanted = query.trim().lowercase()
    return results
        .mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val name = row.string("canonicalName") ?: row.string("species") ?: return@mapNotNull null
            val vernaculars = row.englishVernaculars()
            val extinct = row.bool("extinct") == true
            val exact = !extinct &&
                (vernaculars.any { it.lowercase() == wanted } || name.lowercase() == wanted)
            val (kingdom, taxClass, silhouette) = row.classify()
            SpeciesCandidate(
                extinct = extinct,
                scientificName = name,
                commonName = vernaculars.firstOrNull { it.lowercase() == wanted }
                    ?: vernaculars.firstOrNull(),
                kingdom = kingdom,
                taxClass = taxClass,
                silhouetteResOverride = silhouette,
                usageKey = row.long("speciesKey") ?: row.long("key"),
                rank = row.string("rank"),
                confidence = if (exact) 100 else 0,
                matchKind = if (exact) MatchKind.VERNACULAR_EXACT else MatchKind.VERNACULAR_OTHER,
            )
        }
        .let(::rankVernacularCandidates)
}

/**
 * Exact first, then GBIF's own order, then the fossils. "sparrow" is exactly the
 * *Palaeostruthus eurius* of the GBIF backbone, an extinct bird nobody photographed; a living
 * species the user might actually have seen belongs above it. Applied once per kingdom's
 * results and again over the two kingdoms merged, so an exact plant beats an inexact animal.
 */
internal fun rankVernacularCandidates(candidates: List<SpeciesCandidate>): List<SpeciesCandidate> =
    candidates
        .distinctBy { it.scientificName }
        .sortedWith(compareBy({ it.extinct }, { it.matchKind != MatchKind.VERNACULAR_EXACT }))
        .take(GBIF_CANDIDATE_LIMIT)

/** GBIF's synonyms payload: the canonical names of the usages it has folded into one accepted. */
internal fun parseGbifSynonyms(body: String): List<String> =
    (body.asJsonObject()?.get("results") as? JsonArray)
        .orEmpty()
        .mapNotNull { (it as? JsonObject)?.string("canonicalName") }
        .distinct()

private fun JsonObject.englishVernaculars(): List<String> =
    (this["vernacularNames"] as? JsonArray)
        .orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { it.string("language").let { lang -> lang == null || lang == "eng" } }
        .mapNotNull { it.string("vernacularName") }
        .distinct()

// ---------------------------------------------------------------------------
// Small JSON helpers. These payloads are wide, deeply optional and change shape
// between endpoints, so they are read positionally rather than through @Serializable
// classes that would have to describe forty fields to use six.
// ---------------------------------------------------------------------------

internal fun String.asJsonObject(): JsonObject? =
    runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()

internal fun JsonObject.obj(key: String): JsonObject? =
    runCatching { this[key]?.jsonObject }.getOrNull()

internal fun JsonObject.array(key: String): JsonArray? =
    runCatching { this[key]?.jsonArray }.getOrNull()

internal fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

internal fun JsonObject.int(key: String): Int? =
    runCatching { this[key]?.jsonPrimitive?.int }.getOrNull()

internal fun JsonObject.bool(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.content?.toBooleanStrict() }.getOrNull()

internal fun JsonObject.long(key: String): Long? =
    runCatching { this[key]?.jsonPrimitive?.content?.toLong() }.getOrNull()
