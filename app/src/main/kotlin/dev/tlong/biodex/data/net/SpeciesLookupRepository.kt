package dev.tlong.biodex.data.net

import dev.tlong.biodex.domain.LookupFields
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * ARCHITECTURE.md 5.2's composition: GBIF first because it supplies the scientific name
 * Wikipedia is keyed by, then Wikipedia.
 *
 * Failure of any one source degrades to that field being empty and editable on the card
 * (M19); failure of GBIF leaves the flow with no identity at all, which is the "save with
 * details pending" path (M20).
 */
class SpeciesLookupRepository(
    private val gbif: GbifClient,
    private val wikipedia: WikipediaClient,
) {

    /**
     * The whole lookup for a typed name. [LookupOutcome] is what the confirm card renders.
     *
     * D88: when GBIF has no species by exactly that name, Wikipedia's search is asked which
     * taxon the name means, and its answer — confirmed against GBIF — leads the candidates.
     * Lens and photo captions name things loosely ("cross orb weaver spider"), and GBIF's
     * vernacular search wants a name it holds word for word.
     */
    suspend fun lookup(name: String): LookupOutcome {
        val match = gbif.match(name)
        if (match is LookupResult.Failed) return LookupOutcome.Failed(match.reason)
        val fromGbif = match.valueOrNull()?.candidates.orEmpty()
        val candidates = if (fromGbif.firstOrNull()?.isNameMatch == true) {
            fromGbif
        } else {
            (listOfNotNull(webCandidate(name)) + fromGbif).distinctBy { it.scientificName }.take(GBIF_CANDIDATE_LIMIT)
        }
        if (candidates.isEmpty()) return LookupOutcome.NoMatch
        return LookupOutcome.Resolved(
            candidates = candidates,
            selectedIndex = 0,
            details = detailsFor(candidates.first(), name),
        )
    }

    /**
     * D88's fallback: the taxon Wikipedia's search leads to, when GBIF holds it as a species.
     * A genus or family is dropped — "banana slug" leads to *Ariolimax*, which is not one
     * thing to catch. Any failure here only means no extra candidate.
     */
    private suspend fun webCandidate(name: String): SpeciesCandidate? {
        val taxon = wikipedia.taxonFor(name).valueOrNull() ?: return null
        val best = gbif.match(taxon.scientificName).valueOrNull()?.best ?: return null
        if (!best.isNameMatch || best.rank?.uppercase() !in SPECIES_RANKS) return null
        // A title that is not the science is the article's common name: "Fly agaric".
        val title = taxon.title.takeUnless { it.equals(best.scientificName, ignoreCase = true) }
        return best.copy(matchKind = MatchKind.WEB_SEARCH, commonName = best.commonName ?: title)
    }

    /**
     * The "not this one? other matches" path (M19). Picking a different candidate re-runs the
     * keyed sources, because the habitat text and the picture belong to the species, not to
     * the typed name.
     */
    suspend fun detailsFor(candidate: SpeciesCandidate, typedName: String): CandidateDetails =
        coroutineScope {
            val article = async {
                wikipedia.facts(candidate.scientificName, candidate.commonName ?: typedName)
            }
            val facts = article.await()
            CandidateDetails(
                fields = LookupFields(
                    scientificName = candidate.scientificName,
                    kingdom = candidate.kingdom,
                    taxClass = candidate.taxClass,
                    lineage = candidate.lineage,
                    habitatText = facts.valueOrNull()?.habitatText,
                    description = facts.valueOrNull()?.description,
                    imageUrl = facts.valueOrNull()?.imageUrl,
                    imageAttribution = facts.valueOrNull()?.imageAttribution,
                    infoUrl = facts.valueOrNull()?.infoUrl,
                ),
                habitatSource = facts.valueOrNull()?.habitatSource,
                articleFailed = facts is LookupResult.Failed,
            )
        }
}

/** A rank GBIF leaves out is given the benefit of the doubt; one above species is not. */
private val SPECIES_RANKS = setOf(null, "SPECIES", "SUBSPECIES", "VARIETY", "FORM")

/** What one candidate's supporting sources produced, plus which of them could not be reached. */
data class CandidateDetails(
    val fields: LookupFields,
    val habitatSource: String? = null,
    /** True only when Wikipedia could not be *asked*; "no article" is an ordinary null field. */
    val articleFailed: Boolean = false,
)

sealed interface LookupOutcome {
    /** GBIF resolved the name. [candidates] is best-first; the rest are M19's "other matches". */
    data class Resolved(
        val candidates: List<SpeciesCandidate>,
        val selectedIndex: Int,
        val details: CandidateDetails,
    ) : LookupOutcome {
        val selected: SpeciesCandidate get() = candidates[selectedIndex]
    }

    /** Asked, and nothing in GBIF's backbone matches. The user names it themselves (M20). */
    data object NoMatch : LookupOutcome

    /** Could not ask. Offered as "save with details pending" so a retry costs nothing. */
    data class Failed(val reason: String) : LookupOutcome
}
