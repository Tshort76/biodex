package dev.tlong.animaldex.ui.addspecies

import dev.tlong.animaldex.data.net.CandidateDetails
import dev.tlong.animaldex.data.net.LookupOutcome
import dev.tlong.animaldex.data.net.SpeciesCandidate
import dev.tlong.animaldex.domain.Ecosystem
import dev.tlong.animaldex.domain.SpeciesFields
import dev.tlong.animaldex.domain.USER_DEX_NUMBER_BASE
import dev.tlong.animaldex.domain.UserSpeciesRecord
import dev.tlong.animaldex.domain.detailsPendingFor
import dev.tlong.animaldex.domain.displayDexNumber
import dev.tlong.animaldex.domain.previewFields
import dev.tlong.animaldex.domain.SpeciesSource

/**
 * Frame 6 of `mockup.html` — "Add Your Own Species — confirm" — as one pure function
 * (ARCHITECTURE.md 6.2, and the pattern slices 4–6 used). Everything the card decides is
 * decided here: which candidate is shown, how honestly its confidence is described, which
 * fields the lookup filled, which the user has claimed, whether a call was found, and what the
 * accept button will actually write.
 */

/** What the user has changed on the card, before anything is saved (M19). */
data class ConfirmCardEdits(
    val selectedIndex: Int = 0,
    val showAlternatives: Boolean = false,
    /** The typed values; only the fields named in [editedFields] are read from it. */
    val values: SpeciesFields? = null,
    val editedFields: Set<String> = emptySet(),
    /** D10: ecosystem tags are the one field no API supplies, so they start empty. */
    val ecosystemIds: Set<String> = emptySet(),
    val handEditing: Boolean = false,
)

sealed interface ConfirmSpeciesUiState {

    data object Loading : ConfirmSpeciesUiState

    /** The draft died with the process (or the backfill's species was deleted). */
    data object Missing : ConfirmSpeciesUiState

    data class Card(
        val typedName: String,
        val isBackfill: Boolean,
        val candidates: List<SpeciesCandidate>,
        val selectedIndex: Int,
        val showAlternatives: Boolean,
        val fields: SpeciesFields,
        val editedFields: Set<String>,
        val habitatSource: String?,
        val callAttribution: String?,
        /** True when the lookup could not be made at all — offered as "save for later" (M20). */
        val lookupFailed: Boolean,
        /** True when the lookup ran and GBIF knows no such animal. Not an error. */
        val noMatch: Boolean,
        val ecosystems: List<Ecosystem>,
        val selectedEcosystemIds: Set<String>,
        val dexNumber: Int,
        val handEditing: Boolean,
        val saving: Boolean,
        val error: String? = null,
    ) : ConfirmSpeciesUiState {

        val selectedCandidate: SpeciesCandidate? get() = candidates.getOrNull(selectedIndex)

        val alternatives: List<SpeciesCandidate>
            get() = candidates.filterIndexed { index, _ -> index != selectedIndex }

        /** The mockup's "Not this one? 2 other matches ›". Absent when there is no choice. */
        val alternativesLabel: String?
            get() = alternatives.size.takeIf { it > 0 }
                ?.let { "Not this one? $it other match${if (it == 1) "" else "es"} ›" }

        val dexLabel: String get() = displayDexNumber(dexNumber, SpeciesSource.USER)

        val imageFound: Boolean get() = fields.imageUrl != null
        val habitatFound: Boolean get() = !fields.habitatText.isNullOrBlank()
        val callFound: Boolean get() = fields.callUrl != null

        /**
         * The call row is rendered in every state and says the honest thing (M18). Today that
         * is always "no call found": there is no Xeno-canto API key, so the client answers
         * NotFound without asking (ARCHITECTURE.md 5.4).
         */
        val callRowLabel: String
            get() = if (callFound) "✓ Call found" else "No call found — normal for most animals"

        val habitatLabel: String
            get() = when {
                habitatSource?.startsWith("wikipedia:section") == true -> "Habitat · Wikipedia"
                habitatSource == "wikipedia:lede" -> "Habitat · Wikipedia summary"
                habitatFound -> "Habitat · your words"
                else -> "Habitat"
            }

        /** M20: accepting without a resolved scientific name leaves the lookup owed. */
        val willBeDetailsPending: Boolean get() = detailsPendingFor(fields)

        val acceptLabel: String
            get() = when {
                isBackfill -> "Save these details"
                willBeDetailsPending -> "Add to my dex — $dexLabel ${fields.commonName} (details pending)"
                else -> "Add to my dex — $dexLabel ${fields.commonName}"
            }

        val canAccept: Boolean get() = !saving && fields.commonName.isNotBlank()

        fun isEdited(field: String): Boolean = field in editedFields
    }
}

/**
 * Builds the card. [outcome] is null while the lookup is in flight or when it was never made
 * (the offline path never gets here — it writes immediately, per M20).
 *
 * The field values come from `previewFields`, the same expression the write path uses, so the
 * card cannot show a merge the save would not perform.
 */
fun confirmCardState(
    draft: AddSpeciesDraft,
    outcome: LookupOutcome?,
    details: CandidateDetails?,
    existing: UserSpeciesRecord?,
    edits: ConfirmCardEdits,
    ecosystems: List<Ecosystem>,
    nextDexNumber: Int,
    saving: Boolean = false,
    error: String? = null,
): ConfirmSpeciesUiState.Card {
    val resolved = outcome as? LookupOutcome.Resolved
    val candidates = resolved?.candidates.orEmpty()
    val selectedIndex = edits.selectedIndex.coerceIn(0, maxOf(candidates.size - 1, 0))
    val stored = existing?.fields ?: SpeciesFields(commonName = draft.typedName)
    val locked = existing?.userEditedFields.orEmpty().toSet() + edits.editedFields

    return ConfirmSpeciesUiState.Card(
        typedName = draft.typedName,
        isBackfill = draft.isBackfill,
        candidates = candidates,
        selectedIndex = selectedIndex,
        showAlternatives = edits.showAlternatives,
        fields = previewFields(
            stored = stored,
            lookup = details?.fields,
            lockedFields = locked,
            editValues = edits.values,
            editedNow = edits.editedFields,
        ),
        editedFields = locked,
        habitatSource = details?.habitatSource,
        callAttribution = details?.fields?.callAttribution,
        lookupFailed = outcome is LookupOutcome.Failed,
        noMatch = outcome is LookupOutcome.NoMatch,
        ecosystems = ecosystems,
        selectedEcosystemIds = edits.ecosystemIds,
        dexNumber = existing?.dexNumber ?: nextDexNumber,
        handEditing = edits.handEditing,
        saving = saving,
        error = error,
    )
}

/** Fallback when nothing has been allocated yet; the first user species is U01. */
const val FIRST_USER_DEX_NUMBER = USER_DEX_NUMBER_BASE + 1
