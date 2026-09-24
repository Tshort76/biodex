package dev.tlong.biodex.ui.identify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.photo.CaptureRegistrar
import dev.tlong.biodex.data.photo.PhotoGateway
import dev.tlong.biodex.data.place.PlaceGazetteer
import dev.tlong.biodex.data.repo.DexRepository
import dev.tlong.biodex.domain.PlaceAnswer
import dev.tlong.biodex.ui.capture.PickedPhoto
import dev.tlong.biodex.ui.capture.PlaceSearchState
import dev.tlong.biodex.ui.capture.placeAnswerFor
import dev.tlong.biodex.ui.capture.placeSearchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** D78: where the Identify screen sends the user next. */
sealed interface IdentifyEvent {
    /** Recorded against a species the dex held. [isFirst] plays the reveal (M09). */
    data class Captured(val speciesId: String, val isFirst: Boolean) : IdentifyEvent

    /** The name is new to the dex: the add card takes it, with this photo and its place (Q02). */
    data class Add(val name: String, val photoUri: String, val place: PlaceAnswer?) : IdentifyEvent

    data object Unreadable : IdentifyEvent
}

/**
 * D78. The state function plus `stateIn`, and the two writes-in-waiting a photo can end in.
 * Both need the photo's place, so the place is settled first — read from the EXIF when the
 * screen opens, and asked for (D64) only when an action needs it and the photo had none.
 */
class IdentifyViewModel(
    private val repository: DexRepository,
    private val registrar: CaptureRegistrar,
    private val photos: PhotoGateway,
    private val photo: PickedPhoto,
    private val gazetteer: PlaceGazetteer = PlaceGazetteer.None,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedId = MutableStateFlow<String?>(null)

    /** Set when Lens opens; the next clipboard read fills the search, and only that one. */
    private var lensOpened = false
    private val capturing = MutableStateFlow(false)

    /** Null while the EXIF read runs; then whether the photo carries coordinates. */
    private val located = MutableStateFlow<Boolean?>(null)

    /** The action waiting on the place prompt; null when none is. */
    private val pending = MutableStateFlow<(suspend (PlaceAnswer?) -> Unit)?>(null)
    private val placeQuery = MutableStateFlow("")

    private val events = Channel<IdentifyEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val uiState: StateFlow<IdentifyUiState> = identifyUiState(
        photo = photo,
        species = repository.speciesSummaries(),
        query = query,
        selectedId = selectedId,
        capturing = capturing,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IdentifyUiState(photo))

    val placePrompt: StateFlow<PlaceSearchState?> = combine(
        pending,
        placeSearchState(
            query = placeQuery,
            gazetteer = flow { emit(gazetteer.places()) }.onStart { emit(emptyList()) },
            recent = repository.placeLabels(),
        ).flowOn(Dispatchers.Default),
    ) { action, search -> search.takeIf { action != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val facts = withContext(Dispatchers.IO) { photos.readExif(photo.uri) }
            located.value = facts.lat != null && facts.lng != null
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSelect(speciesId: String) {
        selectedId.value = if (selectedId.value == speciesId) null else speciesId
    }

    fun onLensOpened() {
        lensOpened = true
    }

    /**
     * The route reads the clipboard whenever the screen regains focus; it counts only on the
     * way back from Lens, so something copied before the photo was picked never fills the search.
     */
    fun onClipboard(text: String?) {
        if (!lensOpened) return
        lensOpened = false
        nameFromClipboard(text)?.let { query.value = it }
    }

    fun onCapture() {
        val species = uiState.value.selected ?: return
        if (capturing.value) return
        withPlace { place -> record(species.id, place) }
    }

    fun onAdd() {
        val name = uiState.value.addableName ?: return
        withPlace { place -> events.send(IdentifyEvent.Add(name, photo.uri, place)) }
    }

    fun onPlaceQueryChange(value: String) {
        placeQuery.value = value
    }

    fun onPlaceEntered(typed: String) {
        val answer = placeAnswerFor(typed, placePrompt.value ?: return) ?: return
        val action = pending.value ?: return
        pending.value = null
        placeQuery.value = ""
        viewModelScope.launch { action(answer) }
    }

    fun onPlacePromptDismissed() {
        pending.value = null
        placeQuery.value = ""
    }

    /** Back without a capture: the grant the pick took is handed back unless a capture uses it. */
    fun onLeave() {
        viewModelScope.launch {
            if (repository.captureCountForUri(photo.uri) == 0) photos.releaseGrant(photo.uri)
        }
    }

    /** Runs [action] with no place when the photo has one, and after the prompt when not. */
    private fun withPlace(action: suspend (PlaceAnswer?) -> Unit) {
        viewModelScope.launch {
            if (located.first { it != null } == true) action(null) else pending.value = action
        }
    }

    private suspend fun record(speciesId: String, place: PlaceAnswer?) {
        capturing.value = true
        val result = registrar.register(
            speciesId,
            photo.uri,
            locationLabel = place?.label,
            placeLat = place?.lat,
            placeLng = place?.lng,
        )
        when (result) {
            is CaptureRegistrar.RegisterResult.Registered ->
                events.send(IdentifyEvent.Captured(speciesId, result.isFirst))
            is CaptureRegistrar.RegisterResult.ThumbnailFailed -> events.send(IdentifyEvent.Unreadable)
            // The door's own rule (D60) disagreeing with the EXIF read: ask, rather than fail.
            CaptureRegistrar.RegisterResult.PlaceMissing -> pending.value = { p -> record(speciesId, p) }
        }
        capturing.value = false
    }

    companion object {
        fun factory(container: AppContainer, photo: PickedPhoto): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    IdentifyViewModel(
                        repository = container.dexRepository,
                        registrar = container.captureRegistrar,
                        photos = container.photoGateway,
                        photo = photo,
                        gazetteer = container.placeGazetteer,
                    )
                }
            }
    }
}
