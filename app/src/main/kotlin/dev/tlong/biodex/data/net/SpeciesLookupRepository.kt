package dev.tlong.biodex.data.net

import dev.tlong.biodex.data.catalogue.DUKE_ATTRIBUTION
import dev.tlong.biodex.data.catalogue.DukeIndex
import dev.tlong.biodex.data.catalogue.DukeRecord
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.LookupFields
import dev.tlong.biodex.domain.PlantUse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * ARCHITECTURE.md 5.2's composition: GBIF first because it supplies the scientific name the
 * other two are keyed by, then Wikipedia and Xeno-canto in parallel.
 *
 * Failure of any one source degrades to that field being empty and editable on the card
 * (M19); failure of GBIF leaves the flow with no identity at all, which is the "save with
 * details pending" path (M20).
 *
 * **A plant takes a different second source (M18, 11.4).** Xeno-canto is not asked at all — not
 * skipped late, not asked and ignored — and the bundled Duke's index is consulted in its place.
 * That lookup is offline and in-process, which is what lets M20's offline path keep working
 * unchanged: the Duke's fields arrive with everything else on the backfill.
 */
class SpeciesLookupRepository(
    private val gbif: GbifClient,
    private val wikipedia: WikipediaClient,
    private val xenoCanto: XenoCantoClient,
    private val duke: DukeIndex? = null,
) {

    /** The whole lookup for a typed name. [LookupOutcome] is what the confirm card renders. */
    suspend fun lookup(name: String): LookupOutcome =
        when (val match = gbif.match(name)) {
            is LookupResult.Found -> LookupOutcome.Resolved(
                candidates = match.value.candidates,
                selectedIndex = 0,
                details = detailsFor(match.value.best, name),
            )

            LookupResult.NotFound -> LookupOutcome.NoMatch
            is LookupResult.Failed -> LookupOutcome.Failed(match.reason)
        }

    /**
     * The "not this one? other matches" path (M19). Picking a different candidate re-runs the
     * two keyed sources, because the habitat text and the picture belong to the species, not
     * to the typed name.
     */
    suspend fun detailsFor(candidate: SpeciesCandidate, typedName: String): CandidateDetails =
        coroutineScope {
            val plant = candidate.kingdom == Kingdom.PLANT
            val article = async {
                wikipedia.facts(candidate.scientificName, candidate.commonName ?: typedName)
            }
            // A plant never queries Xeno-canto (M18). Its synonyms are fetched instead, because
            // the Duke's join misses without them: Oregon grape has four records under
            // *Mahonia aquifolium* and none under *Berberis aquifolium* (R15).
            val call = async { if (plant) LookupResult.NotFound else xenoCanto.bestCall(candidate.scientificName) }
            val synonyms = async { if (plant) gbif.synonyms(candidate.usageKey) else emptyList() }
            val facts = article.await()
            val recording = call.await()
            val dukeRecord = if (plant) {
                duke?.lookup(candidate.scientificName, synonyms.await())
            } else {
                null
            }
            CandidateDetails(
                fields = LookupFields(
                    scientificName = candidate.scientificName,
                    kingdom = candidate.kingdom,
                    taxClass = candidate.taxClass,
                    silhouetteResOverride = candidate.silhouetteResOverride,
                    habitatText = facts.valueOrNull()?.habitatText,
                    description = facts.valueOrNull()?.description,
                    imageUrl = facts.valueOrNull()?.imageUrl,
                    imageAttribution = facts.valueOrNull()?.imageAttribution,
                    callUrl = recording.valueOrNull()?.url,
                    callAttribution = recording.valueOrNull()?.attribution,
                    infoUrl = facts.valueOrNull()?.infoUrl,
                    uses = if (plant) derivedUses(dukeRecord) else null,
                    usesNote = if (dukeRecord?.poison == true) POISON_CAUTION else null,
                    medicinalActivities = if (plant) dukeRecord?.activities.orEmpty() else null,
                    medicinalRecordCount = if (plant) dukeRecord?.recordCount ?: 0 else null,
                    usesAttribution = dukeRecord?.let { DUKE_ATTRIBUTION },
                ),
                habitatSource = facts.valueOrNull()?.habitatSource,
                articleFailed = facts is LookupResult.Failed,
                callFailed = recording is LookupResult.Failed,
                duke = dukeRecord,
                dukeConsulted = plant,
            )
        }
}

/**
 * M27's pre-filled caution. It says where the claim comes from, because the app's only claim
 * about a plant is that a use is *documented for the species* (M30) — and a poison record is
 * exactly that kind of claim.
 */
const val POISON_CAUTION = "Caution: recorded as poisonous in Duke's ethnobotanical database."

/**
 * The **medicinal** default only (M27). Edible is never derived — Duke's holds 15 `Food`
 * records in 82,873, so edibility stays the user's own judgment, and the app asserting it
 * would be the one thing D14 and M30 forbid.
 */
private fun derivedUses(record: DukeRecord?): Set<PlantUse> =
    if (DukeIndex.medicinalByRule(record)) setOf(PlantUse.MEDICINAL) else emptySet()

/** What one candidate's supporting sources produced, plus which of them could not be reached. */
data class CandidateDetails(
    val fields: LookupFields,
    val habitatSource: String? = null,
    /** True only when Wikipedia could not be *asked*; "no article" is an ordinary null field. */
    val articleFailed: Boolean = false,
    val callFailed: Boolean = false,
    /** Duke's row for a plant, shown read-only beside the medicinal toggle (M27). */
    val duke: DukeRecord? = null,
    /** True when this candidate is a plant, so "no Duke's record" can be said honestly. */
    val dukeConsulted: Boolean = false,
) {
    val callFound: Boolean get() = fields.callUrl != null
}

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
