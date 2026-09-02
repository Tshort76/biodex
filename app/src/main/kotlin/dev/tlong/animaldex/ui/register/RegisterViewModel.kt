package dev.tlong.animaldex.ui.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.animaldex.AppContainer
import dev.tlong.animaldex.data.photo.CaptureRegistrar
import dev.tlong.animaldex.data.photo.GrantPressure
import dev.tlong.animaldex.data.repo.DexRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The ViewModel is the pure state function plus `stateIn`, and the writes (6.2). One-shot
 * results go out on a `Channel` so the route navigates once rather than on every recomposition.
 */
class RegisterViewModel(
    repository: DexRepository,
    private val registrar: CaptureRegistrar,
    preselectedSpeciesId: String?,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedSpeciesId = MutableStateFlow(preselectedSpeciesId)
    private val photo = MutableStateFlow<PickedPhoto?>(null)
    private val registering = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val events = Channel<RegisterEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val _grantWarning = MutableStateFlow<String?>(null)

    val uiState: StateFlow<RegisterUiState> = combine(
        registerUiState(
            species = repository.speciesSummaries(),
            query = query,
            selectedSpeciesId = selectedSpeciesId,
            photo = photo,
            registering = registering,
            error = error,
        ),
        _grantWarning,
    ) { state, warning -> state.copy(grantWarning = warning) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RegisterUiState(),
        )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSelectSpecies(speciesId: String) {
        selectedSpeciesId.value = if (selectedSpeciesId.value == speciesId) null else speciesId
        error.value = null
    }

    /** Called with the picker's result after the route has taken the persistable grant. */
    fun onPhotoPicked(picked: PickedPhoto?) {
        photo.value = picked
        error.value = null
        if (picked != null) refreshGrantPressure()
    }

    fun onRegister() {
        val speciesId = selectedSpeciesId.value ?: return
        val picked = photo.value ?: return
        if (registering.value) return
        registering.value = true
        viewModelScope.launch {
            when (val result = registrar.register(speciesId, picked.uri)) {
                is CaptureRegistrar.RegisterResult.Registered ->
                    events.send(RegisterEvent.Registered(result.speciesId, result.isFirst))

                is CaptureRegistrar.RegisterResult.ThumbnailFailed -> {
                    error.value = "That photo could not be read. Pick another one — nothing " +
                        "was saved."
                    events.send(RegisterEvent.PhotoUnreadable)
                }
            }
            registering.value = false
        }
    }

    private fun refreshGrantPressure() {
        viewModelScope.launch {
            _grantWarning.value = when (registrar.grantPressure()) {
                GrantPressure.FINE -> null
                GrantPressure.NEAR_CAP ->
                    "This app is close to Android's 5,000 linked-photo limit. Deleting old " +
                        "captures frees links."

                GrantPressure.AT_CAP ->
                    "Android's 5,000 linked-photo limit is reached. New photos may lose their " +
                        "link after a reboot until some captures are deleted."
            }
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            preselectedSpeciesId: String?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RegisterViewModel(
                    repository = container.dexRepository,
                    registrar = container.captureRegistrar,
                    preselectedSpeciesId = preselectedSpeciesId,
                )
            }
        }
    }
}
