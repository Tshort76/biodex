package dev.tlong.biodex.ui.addspecies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.net.CandidateDetails
import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.net.SpeciesLookupRepository
import dev.tlong.biodex.data.repo.AddSpeciesRegistrar
import dev.tlong.biodex.data.repo.DEFAULT_REGION_ID
import dev.tlong.biodex.data.repo.DexRepository
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.SpeciesField
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.UserSpeciesRecord
import dev.tlong.biodex.domain.nextUserDexNumber
import dev.tlong.biodex.media.NetworkMonitor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The confirm card's state holder (M18–M21).
 *
 * It has one branch the rest of the app does not: **offline never reaches the card.** M20 says
 * registration never blocks on the network and an offline add is created immediately from the
 * name and the photo alone, so when the screen opens with no connectivity it writes the
 * details-pending species and hands the route a navigation event. M19's "nothing is written
 * until you accept" governs the lookup path, which is the only path where there is something
 * to confirm.
 */
class ConfirmSpeciesViewModel(
    private val drafts: AddSpeciesDraftHolder,
    private val lookups: SpeciesLookupRepository,
    private val registrar: AddSpeciesRegistrar,
    private val repository: DexRepository,
    private val networkMonitor: NetworkMonitor,
    private val draftId: String,
) : ViewModel() {

    sealed interface Event {
        /** Created from scratch: the route navigates to the detail screen with the reveal. */
        data class Created(val speciesId: String) : Event

        /** A backfill was saved: the route just goes back to the entry it came from. */
        data class Updated(val speciesId: String) : Event

        data object Dismissed : Event
    }

    private val draft = drafts.get(draftId)

    private var outcome: LookupOutcome? = null
    private var details: CandidateDetails? = null
    private var existing: UserSpeciesRecord? = null
    private var edits = ConfirmCardEdits()
    private var ecosystems: List<Ecosystem> = emptyList()
    private var nextDexNumber = FIRST_USER_DEX_NUMBER
    private var saving = false
    private var error: String? = null

    private val _uiState = MutableStateFlow<ConfirmSpeciesUiState>(
        if (draft == null) ConfirmSpeciesUiState.Missing else ConfirmSpeciesUiState.Loading,
    )
    val uiState: StateFlow<ConfirmSpeciesUiState> = _uiState.asStateFlow()

    private val events = Channel<Event>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        if (draft != null) start(draft)
    }

    private fun start(draft: AddSpeciesDraft) = viewModelScope.launch {
        ecosystems = repository.ecosystems().first()
        nextDexNumber = nextUserDexNumber(repository.maxUserDexNumber(DEFAULT_REGION_ID))
        existing = draft.backfillSpeciesId?.let { repository.userSpecies(it) }
        if (draft.isBackfill && existing == null) {
            _uiState.value = ConfirmSpeciesUiState.Missing
            return@launch
        }
        edits = edits.copy(ecosystemIds = existing?.let { record ->
            repository.speciesDetail(record.id).first()?.summary?.ecosystemIds?.toSet()
        }.orEmpty())

        val online = networkMonitor.online.value
        if (!online && !draft.isBackfill) {
            createOfflinePending(draft)
            return@launch
        }

        outcome = draft.prefetched ?: lookups.lookup(draft.typedName)
        details = (outcome as? LookupOutcome.Resolved)?.details
        publish()
    }

    /** M20's offline path: one write, no card, no waiting. */
    private suspend fun createOfflinePending(draft: AddSpeciesDraft) {
        when (
            val result = registrar.create(
                fields = SpeciesFields(commonName = draft.typedName),
                ecosystemIds = emptyList(),
                photoUri = draft.photoUri,
            )
        ) {
            is AddSpeciesRegistrar.CreateResult.Created -> {
                drafts.remove(draftId)
                events.send(Event.Created(result.speciesId))
            }

            AddSpeciesRegistrar.CreateResult.PhotoUnreadable -> {
                error = "That photo could not be read. Nothing was saved."
                publish()
            }
        }
    }

    fun onSelectCandidate(index: Int) {
        val resolved = outcome as? LookupOutcome.Resolved ?: return
        val candidate = resolved.candidates.getOrNull(index) ?: return
        edits = edits.copy(selectedIndex = index, showAlternatives = false)
        details = null
        publish()
        viewModelScope.launch {
            // The habitat text and the picture belong to the species, not to the typed name,
            // so a different candidate means a fresh Wikipedia and Xeno-canto pass.
            details = lookups.detailsFor(candidate, draft?.typedName.orEmpty())
            publish()
        }
    }

    fun onToggleAlternatives() {
        edits = edits.copy(showAlternatives = !edits.showAlternatives)
        publish()
    }

    fun onToggleEcosystem(ecosystemId: String) {
        val selected = edits.ecosystemIds.toMutableSet()
        if (!selected.remove(ecosystemId)) selected.add(ecosystemId)
        edits = edits.copy(ecosystemIds = selected)
        publish()
    }

    fun onToggleHandEditing() {
        edits = edits.copy(handEditing = !edits.handEditing)
        publish()
    }

    /**
     * M21's other half: the moment a field is edited by hand it joins `userEditedFields`, and
     * from then on no backfill — this session's or next year's — may overwrite it.
     */
    fun onEditField(field: String, apply: (SpeciesFields) -> SpeciesFields) {
        val current = (_uiState.value as? ConfirmSpeciesUiState.Card)?.fields ?: return
        edits = edits.copy(
            values = apply(edits.values ?: current),
            editedFields = edits.editedFields + field,
        )
        publish()
    }

    /**
     * M27's mis-resolved-kingdom escape hatch. Switching resets the class to that kingdom's
     * default (11.4) and locks the **kingdom** only: the class stays open so a later backfill
     * that finally reads GBIF's plant class can still fill in a real growth form.
     */
    fun onToggleKingdom() {
        val current = (_uiState.value as? ConfirmSpeciesUiState.Card)?.fields ?: return
        val kingdom = if (current.kingdom == Kingdom.PLANT) Kingdom.ANIMAL else Kingdom.PLANT
        onEditField(SpeciesField.KINGDOM) {
            it.copy(
                kingdom = kingdom,
                taxClass = TaxClass.defaultFor(kingdom),
                silhouetteResOverride = null,
            )
        }
    }

    /**
     * The growth-form / class pick. It claims the **kingdom too**, because otherwise a backfill
     * that re-read GBIF's kingdom would pair the hand-picked class away to the other kingdom's
     * default — the class would be locked and still lost.
     */
    fun onSelectTaxClass(taxClass: TaxClass) {
        onEditField(SpeciesField.TAX_CLASS) {
            it.copy(
                kingdom = taxClass.kingdom,
                taxClass = taxClass,
                silhouetteResOverride = if (taxClass == it.taxClass) it.silhouetteResOverride else null,
            )
        }
        edits = edits.copy(editedFields = edits.editedFields + SpeciesField.KINGDOM)
        publish()
    }

    /** Either use toggle. Both halves live in one field, so touching either locks both (M21). */
    fun onToggleUse(use: PlantUse) {
        onEditField(SpeciesField.USES) {
            it.copy(uses = if (use in it.uses) it.uses - use else it.uses + use)
        }
    }

    fun onEditUsesNote(text: String) {
        onEditField(SpeciesField.USES_NOTE) { it.copy(usesNote = text.ifBlank { null }) }
    }

    fun onAccept() {
        val draft = draft ?: return
        val card = _uiState.value as? ConfirmSpeciesUiState.Card ?: return
        if (saving) return
        saving = true
        error = null
        publish()
        viewModelScope.launch {
            val speciesId = existing?.id
            if (speciesId != null) {
                registrar.backfill(
                    speciesId = speciesId,
                    lookup = details?.fields,
                    edits = AddSpeciesRegistrar.FieldEdits(edits.values, edits.editedFields.toList()),
                    ecosystemIds = card.selectedEcosystemIds.toList(),
                )
                drafts.remove(draftId)
                events.send(Event.Updated(speciesId))
                return@launch
            }
            when (
                val result = registrar.create(
                    fields = card.fields,
                    ecosystemIds = card.selectedEcosystemIds.toList(),
                    photoUri = draft.photoUri,
                    userEditedFields = edits.editedFields.toList(),
                )
            ) {
                is AddSpeciesRegistrar.CreateResult.Created -> {
                    drafts.remove(draftId)
                    events.send(Event.Created(result.speciesId))
                }

                AddSpeciesRegistrar.CreateResult.PhotoUnreadable -> {
                    saving = false
                    error = "That photo could not be read. Nothing was saved — pick another one."
                    publish()
                }
            }
        }
    }

    fun onDismiss() {
        drafts.remove(draftId)
        viewModelScope.launch { events.send(Event.Dismissed) }
    }

    private fun publish() {
        val draft = draft ?: return
        _uiState.value = confirmCardState(
            draft = draft,
            outcome = outcome,
            details = details,
            existing = existing,
            edits = edits,
            ecosystems = ecosystems,
            nextDexNumber = nextDexNumber,
            saving = saving,
            error = error,
        )
    }

    companion object {
        fun factory(container: AppContainer, draftId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ConfirmSpeciesViewModel(
                        drafts = container.addSpeciesDrafts,
                        lookups = container.speciesLookupRepository,
                        registrar = container.addSpeciesRegistrar,
                        repository = container.dexRepository,
                        networkMonitor = container.networkMonitor,
                        draftId = draftId,
                    )
                }
            }
    }
}
