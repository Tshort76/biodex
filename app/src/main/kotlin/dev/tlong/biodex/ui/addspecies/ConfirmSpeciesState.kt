package dev.tlong.biodex.ui.addspecies

import dev.tlong.biodex.data.net.CandidateDetails
import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.net.SpeciesCandidate
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesField
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.USER_DEX_NUMBER_BASE
import dev.tlong.biodex.domain.UserSpeciesRecord
import dev.tlong.biodex.domain.detailsPendingFor
import dev.tlong.biodex.domain.displayDexNumber
import dev.tlong.biodex.domain.SearchMatch
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.previewFields

/**
 * Frame 6 of `mockup.html` — "Add Your Own Species — confirm" — as one pure function
 * (ARCHITECTURE.md 6.2, and the pattern slices 4–6 used). Everything the card decides is
 * decided here: which candidate is shown, how honestly its confidence is described, which
 * fields the lookup filled, which the user has claimed, and what the accept button will
 * actually write.
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

    /**
     * D69. The species is in the dex, uncaught, and the screen asks whether it has been caught:
     * "Not yet" goes home, "Yes" goes to its entry, where Register this species is the ordinary
     * way to record the catch. Only a new species gets here — a backfill (M20) just goes back.
     */
    data class Added(
        val speciesId: String,
        /** "U07 Pacific Wren". */
        val title: String,
        /** Saved without a scientific name: the lookup is still owed (M20). */
        val detailsPending: Boolean,
    ) : ConfirmSpeciesUiState

    data class Card(
        val typedName: String,
        val isBackfill: Boolean,
        val candidates: List<SpeciesCandidate>,
        val selectedIndex: Int,
        val showAlternatives: Boolean,
        val fields: SpeciesFields,
        val editedFields: Set<String>,
        val habitatSource: String?,
        /** True when the lookup could not be made at all — offered as "save for later" (M20). */
        val lookupFailed: Boolean,
        /** True when the lookup ran and GBIF knows no such animal. Not an error. */
        val noMatch: Boolean,
        val ecosystems: List<Ecosystem>,
        val selectedEcosystemIds: Set<String>,
        val dexNumber: Int,
        val handEditing: Boolean,
        val saving: Boolean,
        /**
         * The name exactly as it is being typed, while a hand-edit is open. [fields] carries
         * the formatted spelling (M45), and a text field bound to that would fight the typist —
         * a trailing space vanishes, "o" flips to "O" and back on "of". The editors read these
         * and the read-only rows read [fields], so the card still shows what will be saved.
         */
        val typedCommonName: String? = null,
        val typedScientificName: String? = null,
        /** D69. The dex already holds what the card resolved to, so it offers that entry instead. */
        val alreadyHeld: HeldSpecies? = null,
        /** D69. A held species the typed name is a letter or two off — offered, never enforced. */
        val nearMiss: HeldSpecies? = null,
    ) : ConfirmSpeciesUiState {

        val selectedCandidate: SpeciesCandidate? get() = candidates.getOrNull(selectedIndex)

        val alternatives: List<SpeciesCandidate>
            get() = candidates.filterIndexed { index, _ -> index != selectedIndex }

        /** The mockup's "Not this one? 2 other matches ›". Absent when there is no choice. */
        val alternativesLabel: String?
            get() = alternatives.size.takeIf { it > 0 }
                ?.let { "Not this one? $it other match${if (it == 1) "" else "es"} ›" }

        val dexLabel: String get() = displayDexNumber(dexNumber, SpeciesSource.USER, kingdom)

        val kingdom: Kingdom get() = fields.kingdom
        val isFungus: Boolean get() = kingdom == Kingdom.FUNGUS

        /** The picker offers this kingdom's classes only — never "mushroom" for a sparrow. */
        val offeredClasses: List<TaxClass> get() = TaxClass.of(kingdom)

        /** "Amanita muscaria · fungus · mushroom" — the kingdom beside the class in the match row. */
        val identityLine: String
            get() = listOfNotNull(fields.scientificName, kingdom.wireName, fields.taxClass.wireName)
                .joinToString(" · ")

        val imageFound: Boolean get() = fields.imageUrl != null
        val habitatFound: Boolean get() = !fields.habitatText.isNullOrBlank()

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

        val canAccept: Boolean get() = !saving && fields.commonName.isNotBlank() && alreadyHeld == null

        fun isEdited(field: String): Boolean = field in editedFields
    }
}

/** "#34 Western Tanager" — a species already in the dex that the card would duplicate. */
data class HeldSpecies(val speciesId: String, val title: String)

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
    /** The dex as it stands, so a typo that GBIF corrects to a held species is caught (D69). */
    held: List<SpeciesSummary> = emptyList(),
): ConfirmSpeciesUiState.Card {
    val resolved = outcome as? LookupOutcome.Resolved
    val candidates = resolved?.candidates.orEmpty()
    val selectedIndex = edits.selectedIndex.coerceIn(0, maxOf(candidates.size - 1, 0))
    val stored = existing?.fields ?: SpeciesFields(commonName = draft.typedName)
    val locked = existing?.userEditedFields.orEmpty().toSet() + edits.editedFields

    val fields = previewFields(
        stored = stored,
        lookup = details?.fields,
        lockedFields = locked,
        editValues = edits.values,
        editedNow = edits.editedFields,
    )

    return ConfirmSpeciesUiState.Card(
        typedName = draft.typedName,
        isBackfill = draft.isBackfill,
        candidates = candidates,
        selectedIndex = selectedIndex,
        showAlternatives = edits.showAlternatives,
        fields = fields,
        editedFields = locked,
        habitatSource = details?.habitatSource,
        lookupFailed = outcome is LookupOutcome.Failed,
        noMatch = outcome is LookupOutcome.NoMatch,
        ecosystems = ecosystems,
        selectedEcosystemIds = edits.ecosystemIds,
        dexNumber = existing?.dexNumber ?: nextDexNumber,
        handEditing = edits.handEditing,
        saving = saving,
        typedCommonName = edits.values?.commonName
            ?.takeIf { SpeciesField.COMMON_NAME in edits.editedFields },
        typedScientificName = edits.values?.scientificName
            ?.takeIf { SpeciesField.SCIENTIFIC_NAME in edits.editedFields },
        alreadyHeld = heldMatch(fields, held, exceptId = existing?.id),
        nearMiss = nearMissFor(draft.typedName, held, exceptId = existing?.id),
    )
}

/**
 * D69. The held species the grid's forgiving search would show for [typedName], when GBIF did
 * not correct the typo itself. A suggestion only: "Pacific Wren" is near "Pacific Tree Frog".
 */
internal fun nearMissFor(typedName: String, held: List<SpeciesSummary>, exceptId: String? = null): HeldSpecies? =
    held.firstOrNull { it.id != exceptId && SearchMatch.matches(it.commonName, typedName) }?.let(::heldSpecies)

/**
 * D69. The species in [held] that [fields] would duplicate: the same scientific name, or failing
 * one, the same common name, folded. The grid's ＋ adds any name no species contains word for
 * word, so "Western Tanagre" reaches here, and GBIF answers *Piranga ludoviciana*, which the
 * catalogue already holds.
 */
internal fun heldMatch(fields: SpeciesFields, held: List<SpeciesSummary>, exceptId: String? = null): HeldSpecies? {
    fun same(a: String?, b: String?) = a != null && b != null && SearchMatch.fold(a) == SearchMatch.fold(b)
    val match = held.firstOrNull { it.id != exceptId && same(it.scientificName, fields.scientificName) }
        ?: held.firstOrNull { it.id != exceptId && same(it.commonName, fields.commonName) }
        ?: return null
    return heldSpecies(match)
}

private fun heldSpecies(s: SpeciesSummary) =
    HeldSpecies(s.id, "${displayDexNumber(s.dexNumber, s.source, s.kingdom)} ${s.commonName}")

/** D69. What the screen says once [fields] has been written as [speciesId] under [dexNumber]. */
fun addedState(speciesId: String, dexNumber: Int, fields: SpeciesFields): ConfirmSpeciesUiState.Added =
    ConfirmSpeciesUiState.Added(
        speciesId = speciesId,
        title = "${displayDexNumber(dexNumber, SpeciesSource.USER, fields.kingdom)} ${fields.commonName}",
        detailsPending = detailsPendingFor(fields),
    )

/** Fallback when nothing has been allocated yet; the first user species is U01. */
const val FIRST_USER_DEX_NUMBER = USER_DEX_NUMBER_BASE + 1
