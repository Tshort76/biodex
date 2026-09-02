package dev.tlong.biodex.ui.photoviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.photo.CaptureRegistrar
import dev.tlong.biodex.data.photo.PhotoRef
import dev.tlong.biodex.data.repo.DexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoViewerViewModel(
    private val repository: DexRepository,
    private val registrar: CaptureRegistrar,
    private val captureId: String,
) : ViewModel() {

    private val captureFlow = repository.capture(captureId)
    private val ref = MutableStateFlow<PhotoRef?>(null)

    private val events = Channel<PhotoViewerEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    @Suppress("OPT_IN_USAGE")
    val uiState: StateFlow<PhotoViewerUiState> = photoViewerUiState(
        capture = captureFlow,
        speciesDetail = captureFlow.filterNotNull()
            .flatMapLatest { repository.speciesDetail(it.speciesId) },
        entry = captureFlow.filterNotNull().flatMapLatest { repository.entry(it.speciesId) },
        ref = ref,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PhotoViewerUiState(),
    )

    init {
        resolve()
    }

    /**
     * The probe is a content-resolver call, so it runs off the main thread and its result
     * lands as state rather than being read during composition.
     */
    fun resolve() {
        viewModelScope.launch {
            val capture = captureFlow.first() ?: return@launch
            ref.value = withContext(Dispatchers.IO) { registrar.resolve(capture) }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val state = uiState.value
            val capture = state.capture ?: return@launch
            // Clearing the favorite is legal: the entry then shows its earliest capture.
            repository.setFavoriteCapture(
                capture.speciesId,
                if (state.isFavorite) null else capture.id,
            )
        }
    }

    /** S07. The caller shows the "this reverts the species to uncaught" warning first. */
    fun delete() {
        viewModelScope.launch {
            val plan = registrar.deleteCapture(captureId)
            events.send(PhotoViewerEvent.Deleted(revertedToUncaught = plan?.deleteEntry == true))
        }
    }

    fun relink(newUri: String) {
        viewModelScope.launch {
            if (registrar.relink(captureId, newUri)) {
                resolve()
            } else {
                events.send(PhotoViewerEvent.RelinkFailed)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, captureId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PhotoViewerViewModel(
                        repository = container.dexRepository,
                        registrar = container.captureRegistrar,
                        captureId = captureId,
                    )
                }
            }
    }
}

sealed interface PhotoViewerEvent {
    data class Deleted(val revertedToUncaught: Boolean) : PhotoViewerEvent
    data object RelinkFailed : PhotoViewerEvent
}
