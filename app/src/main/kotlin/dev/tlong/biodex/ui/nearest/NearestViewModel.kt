package dev.tlong.biodex.ui.nearest

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

/** [nearestUiState] plus `stateIn`, and nothing else of substance. */
class NearestViewModel(
    repository: DexRepository,
    speciesId: String?,
) : ViewModel() {

    val uiState: StateFlow<NearestUiState> = nearestUiState(
        species = repository.speciesSummaries(),
        speciesId = speciesId,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NearestUiState(),
    )

    companion object {
        fun factory(container: AppContainer, speciesId: String?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    NearestViewModel(
                        repository = container.dexRepository,
                        speciesId = speciesId,
                    )
                }
            }
    }
}
