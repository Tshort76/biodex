package dev.tlong.animaldex.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.animaldex.AppContainer
import dev.tlong.animaldex.data.repo.DexRepository
import dev.tlong.animaldex.media.CallPlayer
import dev.tlong.animaldex.media.NetworkMonitor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class EntryDetailViewModel(
    repository: DexRepository,
    private val callPlayer: CallPlayer,
    networkMonitor: NetworkMonitor,
    speciesId: String,
) : ViewModel() {

    val uiState: StateFlow<EntryDetailUiState> = entryDetailUiState(
        detail = repository.speciesDetail(speciesId),
        ecosystems = repository.ecosystems(),
        captures = repository.captures(speciesId),
        progress = repository.dexProgress(),
        playback = callPlayer.playback,
        online = networkMonitor.online,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EntryDetailUiState(),
    )

    /** M06: one tap plays, the next stops. The row decides which by reading `callRow`. */
    fun toggleCall(url: String) = callPlayer.toggle(url)

    /**
     * Leaving the screen silences the call. `stop`, never `release`: the player belongs to the
     * container and outlives every screen that uses it.
     */
    override fun onCleared() {
        callPlayer.stop()
    }

    companion object {
        fun factory(container: AppContainer, speciesId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    EntryDetailViewModel(
                        repository = container.dexRepository,
                        callPlayer = container.callPlayer,
                        networkMonitor = container.networkMonitor,
                        speciesId = speciesId,
                    )
                }
            }
    }
}
