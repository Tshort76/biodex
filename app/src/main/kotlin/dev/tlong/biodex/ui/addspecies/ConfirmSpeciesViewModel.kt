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
import dev.tlong.biodex.domain.SpeciesField
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.UserSpeciesRecord
import dev.tlong.biodex.domain.nextUserDexNumber
import dev.tlong.biodex.domain.normalized
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
 * adding never blocks on the network and an offline add is created immediately from the name
 * alone, so when the screen opens with no connectivity it writes the details-pending species
 * straight away. M19's "nothing is written until you accept" governs the lookup path, which is
 * the only path where there is something to confirm.
 *
 * Adding is not catching (D69): both paths end on [ConfirmSpeciesUiState.Added], which asks
 * whether the species has been caught, and the answer is an event the route navigates on.
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
        /** D69: added, not caught yet — the route goes back to the grid. */
        data class NotCaught(val speciesId: String) : Event

        /** D69: added and already caught — the route opens its entry to register the photo. */
        data class Caught(val speciesId: String) : Event

        /** D69: the dex already holds what the card resolved to — the route opens that entry. */
        data class OpenExisting(val speciesId: String) : Event

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
    private var held: List<SpeciesSummary> = emptyList()
    private var saving = false

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
        held = repository.speciesSummaries().first()
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

    /** M20's offline path: one write, no card, no waiting — then the same question (D69). */
    private suspend fun createOfflinePending(draft: AddSpeciesDraft) {
        val fields = SpeciesFields(commonName = draft.typedName)
        val created = registrar.create(fields = fields, ecosystemIds = emptyList())
        drafts.remove(draftId)
        _uiState.value = addedState(created.speciesId, created.dexNumber, fields.normalized())
    }

    fun onSelectCandidate(index: Int) {
        val resolved = outcome as? LookupOutcome.Resolved ?: return
        val candidate = resolved.candidates.getOrNull(index) ?: return
        edits = edits.copy(selectedIndex = index, showAlternatives = false)
        details = null
        publish()
        viewModelScope.launch {
            // The habitat text and the picture belong to the species, not to the typed name,
            // so a different candidate means a fresh Wikipedia pass.
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
     * M27's mis-resolved-kingdom escape hatch, between the two kingdoms the app keeps (it was
     * animal↔plant until D59). Switching resets the class to that kingdom's default (11.4)
     * and locks the **kingdom** only: the class stays open so a later backfill can still fill
     * in a real growth form.
     */
    fun onToggleKingdom() {
        val current = (_uiState.value as? ConfirmSpeciesUiState.Card)?.fields ?: return
        val kingdom = if (current.kingdom == Kingdom.FUNGUS) Kingdom.ANIMAL else Kingdom.FUNGUS
        onEditField(SpeciesField.KINGDOM) {
            it.copy(kingdom = kingdom, taxClass = TaxClass.defaultFor(kingdom))
        }
    }

    /**
     * The growth-form / class pick. It claims the **kingdom too**, because otherwise a backfill
     * that re-read GBIF's kingdom would pair the hand-picked class away to the other kingdom's
     * default — the class would be locked and still lost.
     */
    fun onSelectTaxClass(taxClass: TaxClass) {
        onEditField(SpeciesField.TAX_CLASS) {
            it.copy(kingdom = taxClass.kingdom, taxClass = taxClass)
        }
        edits = edits.copy(editedFields = edits.editedFields + SpeciesField.KINGDOM)
        publish()
    }

    fun onAccept() {
        val draft = draft ?: return
        val card = _uiState.value as? ConfirmSpeciesUiState.Card ?: return
        if (saving) return
        saving = true
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
            val created = registrar.create(
                fields = card.fields,
                ecosystemIds = card.selectedEcosystemIds.toList(),
                userEditedFields = edits.editedFields.toList(),
            )
            drafts.remove(draftId)
            _uiState.value = addedState(created.speciesId, created.dexNumber, card.fields)
        }
    }

    /** D69. Leaves the card for the entry the dex already holds; nothing is written. */
    fun onOpenExisting() {
        val card = _uiState.value as? ConfirmSpeciesUiState.Card ?: return
        val existingId = (card.alreadyHeld ?: card.nearMiss)?.speciesId ?: return
        drafts.remove(draftId)
        viewModelScope.launch { events.send(Event.OpenExisting(existingId)) }
    }

    /** D69's two answers. Asked only once the species exists, so neither writes anything. */
    fun onNotCaught() = answer { Event.NotCaught(it) }

    fun onCaught() = answer { Event.Caught(it) }

    private fun answer(event: (String) -> Event) {
        val added = _uiState.value as? ConfirmSpeciesUiState.Added ?: return
        viewModelScope.launch { events.send(event(added.speciesId)) }
    }

    fun onDismiss() {
        drafts.remove(draftId)
        viewModelScope.launch { events.send(Event.Dismissed) }
    }

    private fun publish() {
        val draft = draft ?: return
        // A lookup still in flight must not paint the card back over the question (D69).
        if (_uiState.value is ConfirmSpeciesUiState.Added) return
        _uiState.value = confirmCardState(
            draft = draft,
            outcome = outcome,
            details = details,
            existing = existing,
            edits = edits,
            ecosystems = ecosystems,
            nextDexNumber = nextDexNumber,
            saving = saving,
            held = held,
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
