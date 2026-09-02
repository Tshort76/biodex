package dev.tlong.biodex.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.repo.DexRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * ARCHITECTURE.md 6.2/6.3: the Stats screen reads the *same* `dexProgress()` flow the grid
 * header reads, so the two can never disagree. The composition itself is the pure
 * `statsUiState`; this class is that function plus a sharing policy.
 */
class StatsViewModel(repository: DexRepository) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = statsUiState(
        progress = repository.dexProgress(),
        species = repository.speciesSummaries(),
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState(),
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { StatsViewModel(container.dexRepository) }
        }
    }
}
