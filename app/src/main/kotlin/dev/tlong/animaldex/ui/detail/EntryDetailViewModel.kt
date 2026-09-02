package dev.tlong.animaldex.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.animaldex.AppContainer
import dev.tlong.animaldex.data.repo.DexRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class EntryDetailViewModel(
    repository: DexRepository,
    speciesId: String,
) : ViewModel() {

    val uiState: StateFlow<EntryDetailUiState> = entryDetailUiState(
        detail = repository.speciesDetail(speciesId),
        ecosystems = repository.ecosystems(),
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EntryDetailUiState(),
    )

    companion object {
        fun factory(container: AppContainer, speciesId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { EntryDetailViewModel(container.dexRepository, speciesId) }
            }
    }
}
