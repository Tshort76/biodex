package dev.tlong.biodex.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.repo.DexRepository
import dev.tlong.biodex.data.settings.AppSettings
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.TaxClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * ARCHITECTURE.md 6.2: one `StateFlow<DexGridUiState>`, composed over the repository's cold
 * flows. The composition itself lives in `DexGridState.kt` as a pure function so it is unit
 * testable; this class only owns the two mutable inputs and the sharing policy.
 */
class DexGridViewModel(
    repository: DexRepository,
    private val settings: AppSettings,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filters = MutableStateFlow(DexGridFilters())

    // D47: the grid opens in the order the user last chose, wherever they chose it — the Sort
    // dropdown and the Settings screen are two doors onto one preference, not two settings.
    // Collected rather than read once, so a change made in Settings reaches this screen.
    private val sort = settings.dexSort.map(::dexSortFromWireName)

    val uiState: StateFlow<DexGridUiState> = dexGridUiState(
        species = repository.speciesSummaries(),
        ecosystems = repository.ecosystems(),
        progress = repository.dexProgress(),
        query = query,
        filters = filters,
        sort = sort,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DexGridUiState(),
    )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onCaughtFilter(value: CaughtFilter) = filters.update {
        it.copy(caught = if (it.caught == value) CaughtFilter.ALL else value)
    }

    fun onUseFilter(value: PlantUse) = filters.update {
        it.copy(use = if (it.use == value) null else value)
    }

    /** Tapping the selected chip clears it — the mockup has no separate "clear" affordance. */
    fun onClassFilter(value: TaxClass) = filters.update {
        it.copy(taxClass = if (it.taxClass == value) null else value)
    }

    fun onEcosystemFilter(value: String) = filters.update {
        it.copy(ecosystemId = if (it.ecosystemId == value) null else value)
    }

    /**
     * Set, never toggle: the grid always has an order, so there is no "no sort" to fall back
     * to (D32). This is also why [onClearFilters] does not touch it.
     */
    fun onSort(value: DexSort) {
        settings.setDexSort(value.wireName)
    }

    fun onClearFilters() {
        filters.value = DexGridFilters()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DexGridViewModel(container.dexRepository, container.settings) }
        }
    }
}
