package dev.tlong.biodex.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.net.SpeciesLookupRepository
import dev.tlong.biodex.data.repo.DexRepository
import dev.tlong.biodex.media.NetworkMonitor
import dev.tlong.biodex.ui.addspecies.AddSpeciesDraftHolder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EntryDetailViewModel(
    private val repository: DexRepository,
    private val networkMonitor: NetworkMonitor,
    private val lookups: SpeciesLookupRepository,
    private val drafts: AddSpeciesDraftHolder,
    private val speciesId: String,
) : ViewModel() {

    val uiState: StateFlow<EntryDetailUiState> = entryDetailUiState(
        detail = repository.speciesDetail(speciesId),
        ecosystems = repository.ecosystems(),
        captures = repository.captures(speciesId),
        progress = repository.dexProgress(),
        online = networkMonitor.online,
        rangeGrid = repository.rangeGrid(),
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EntryDetailUiState(),
    )

    private val backfills = Channel<String>(Channel.BUFFERED)

    /** M20: emits a draft id when a details-pending entry's lookup came back with something. */
    val backfillEvents = backfills.receiveAsFlow()

    init {
        viewModelScope.launch { maybeBackfill(repository) }
    }

    /** M46: the picture toggle under the hero. A write, so the ViewModel owns it. */
    fun onPreferOwnPhoto(preferOwnPhoto: Boolean) {
        viewModelScope.launch { repository.setPreferOwnPhoto(speciesId, preferOwnPhoto) }
    }

    /**
     * M20's backfill trigger: "the app backfills automatically the next time it is online and
     * the entry is opened, then presents the same confirmation card."
     *
     * Two deliberate choices. It **looks up but does not write** — M19's rule that nothing is
     * saved until the user accepts governs here too, and a silent write is exactly the
     * corruption D10 exists to prevent. And a lookup that finds nothing, or cannot be made,
     * presents nothing: the entry stays pending and the next open tries again.
     *
     * A missing classification re-enters the trigger on its own (D36). A species added before
     * the path existed is not "pending" — it has its picture, its habitat and its name — so
     * without this it could never acquire one, and Nearest would shut it out permanently while
     * telling the user to open it online. The guard widens here rather than in
     * `detailsPending`, which also drives the hero and the map frame: an entry with a picture
     * must not start rendering as though it had none.
     */
    private suspend fun maybeBackfill(repository: DexRepository) {
        val detail = repository.speciesDetail(speciesId).first { it != null } ?: return
        if (!detail.summary.detailsPending && detail.summary.lineage.isKnown) return
        if (!networkMonitor.online.value) return
        val outcome = lookups.lookup(detail.summary.commonName)
        if (outcome !is LookupOutcome.Resolved) return
        backfills.send(
            drafts.put(
                typedName = detail.summary.commonName,
                backfillSpeciesId = speciesId,
                prefetched = outcome,
            ),
        )
    }

    companion object {
        fun factory(container: AppContainer, speciesId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    EntryDetailViewModel(
                        repository = container.dexRepository,
                        networkMonitor = container.networkMonitor,
                        lookups = container.speciesLookupRepository,
                        drafts = container.addSpeciesDrafts,
                        speciesId = speciesId,
                    )
                }
            }
    }
}
