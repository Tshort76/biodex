package dev.tlong.biodex.data.identify

import dev.tlong.biodex.data.net.GbifClient
import dev.tlong.biodex.data.net.LookupResult
import dev.tlong.biodex.data.net.MatchKind
import dev.tlong.biodex.data.net.SpeciesCandidate
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSummary

/**
 * Validation and catalogue matching (§6.2, M32/M33) — the half of identification that decides
 * what the user is allowed to see.
 *
 * Every service considered in §2 will occasionally return a name that does not exist, is
 * misspelled, or is a synonym the catalogue does not use, and every one of them will say it
 * fluently. So no raw provider string reaches the screen: each is resolved through the
 * `GbifClient.match` the add-your-own path already uses, and only what survives is shown.
 */
class CandidateResolver(
    private val gbif: GbifClient,
    /** §6.2: five GBIF calls per identification, through the existing 20 MB HTTP cache. */
    private val maxCandidates: Int = MAX_RESULTS,
) {

    /**
     * @param catalogue every species the app knows, curated and user-added alike. A plant the
     *        user added themselves months ago must resolve to its own `U`-number rather than
     *        reading "not in dex", which is why this is the full summary list and not the
     *        curated subset.
     */
    suspend fun resolve(
        candidates: List<IdCandidate>,
        catalogue: List<SpeciesSummary>,
        kingdom: Kingdom,
    ): LookupResult<Resolution> {
        val resolved = mutableListOf<ResolvedCandidate>()
        var dropped = 0
        var firstCall = true

        for (candidate in candidates.take(maxCandidates)) {
            when (val match = gbif.match(candidate.scientificName)) {
                is LookupResult.Found -> {
                    val best = match.value.best
                    if (isDisplayable(best, kingdom)) {
                        resolved += ResolvedCandidate(
                            candidate = candidate,
                            gbif = match.value,
                            catalogueSpeciesId = catalogueMatch(best.scientificName, catalogue),
                        )
                    } else {
                        dropped++
                    }
                }

                LookupResult.NotFound -> dropped++

                // 5.2's rule, applied to a chain of calls: if the *first* name could not be
                // asked about at all the app is offline or GBIF is down, and reporting "1 name
                // dropped" would tell the user something false about their photo. A failure
                // once something has resolved is that one name's problem, not the run's.
                is LookupResult.Failed ->
                    if (firstCall) return LookupResult.Failed(match.reason) else dropped++
            }
            firstCall = false
        }

        return if (resolved.isEmpty()) {
            LookupResult.NotFound
        } else {
            LookupResult.Found(Resolution(candidates = resolved, dropped = dropped))
        }
    }
}

/** What survived validation, and how many did not — the panel's heading needs both (M32). */
data class Resolution(
    val candidates: List<ResolvedCandidate>,
    /** Names GBIF did not recognise, or matched only to a broader group. */
    val dropped: Int,
)

/**
 * One candidate the app may display: the provider's suggestion, GBIF's reading of it, and the
 * catalogue row it corresponds to when there is one.
 */
data class ResolvedCandidate(
    val candidate: IdCandidate,
    val gbif: dev.tlong.biodex.data.net.GbifMatch,
    /** Null when the species is not in this install's catalogue — the "add your own" case. */
    val catalogueSpeciesId: String?,
) {
    /** GBIF's accepted name, which is what the app shows — never the provider's raw string. */
    val scientificName: String get() = gbif.best.scientificName

    val commonName: String? get() = candidate.commonName ?: gbif.best.commonName

    val inCatalogue: Boolean get() = catalogueSpeciesId != null
}

/**
 * M32's strictness, and the reason it exists.
 *
 * `GbifClient.match` runs with `strict=false`, and its parser returns `Found` for a
 * `HIGHERRANK` match — so **an invented epithet comes back as its genus**, with no `species`
 * field and a name that looks entirely legitimate. That is the Roosevelt Elk failure (D10) one
 * step earlier: a classifier that hallucinates *Mahonia inventata* would otherwise be shown as
 * a resolved *Mahonia*, and the user would have no way to tell. So only a match at species or
 * subspecies rank survives, and a higher-rank match is dropped and counted like a name GBIF
 * never heard of.
 *
 * The kingdom check is the same rule from the other direction: a lichen or a slime mould that
 * a plant classifier names is not a plant, and GBIF is the one that knows.
 */
internal fun isDisplayable(best: SpeciesCandidate, kingdom: Kingdom): Boolean {
    if (best.matchKind != MatchKind.EXACT && best.matchKind != MatchKind.FUZZY) return false
    if (best.rank?.uppercase() !in ACCEPTED_RANKS) return false
    return best.kingdom == kingdom
}

private val ACCEPTED_RANKS = setOf("SPECIES", "SUBSPECIES")

/**
 * M33: the catalogue is matched on **scientific name, first two tokens, case-folded** — never
 * on common name, which is ambiguous across regions and is exactly what D10 refuses to trust.
 *
 * Two tokens rather than the whole string, because the catalogue carries subspecies-level rows:
 * `Cervus canadensis roosevelti` is one binomial's worth of Roosevelt Elk, and a service that
 * answers at species level would otherwise miss it. Both sides are GBIF-accepted names — the
 * catalogue's own `provenance.scientificName` is `gbif` — so accepted-to-accepted is the right
 * comparison and needs no synonym data (C11 is the residual, for backbone drift).
 *
 * Returns the first match in dex order when two catalogue rows share a binomial, which is
 * stable rather than arbitrary.
 */
internal fun catalogueMatch(scientificName: String, catalogue: List<SpeciesSummary>): String? {
    val wanted = binomialKey(scientificName) ?: return null
    return catalogue
        .sortedBy { it.dexNumber }
        .firstOrNull { binomialKey(it.scientificName) == wanted }
        ?.id
}

/** Genus and specific epithet, case-folded — the normalisation `DukeIndex` already uses. */
internal fun binomialKey(name: String?): String? {
    val parts = name?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
    if (parts.size < 2) return null
    return (parts[0] + " " + parts[1]).lowercase()
}
