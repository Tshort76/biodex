package dev.tlong.biodex.ui.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.photo.CaptureRegistrar
import dev.tlong.biodex.data.photo.GrantPressure
import dev.tlong.biodex.data.photo.PhotoGateway
import dev.tlong.biodex.data.place.PlaceGazetteer
import dev.tlong.biodex.data.photo.PhotoSourceKind
import dev.tlong.biodex.data.photo.shouldDeleteCacheFile
import dev.tlong.biodex.data.photo.shouldPromoteToGallery
import dev.tlong.biodex.data.repo.DexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ViewModel is the pure state function plus `stateIn`, and the writes (6.2). One-shot
 * results go out on a `Channel` so the route navigates once rather than on every recomposition.
 */
class RegisterViewModel(
    private val repository: DexRepository,
    private val registrar: CaptureRegistrar,
    private val preselectedSpeciesId: String?,
    private val photos: PhotoGateway,
    private val gazetteer: PlaceGazetteer = PlaceGazetteer.None,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedSpeciesId = MutableStateFlow(preselectedSpeciesId)
    private val photo = MutableStateFlow<PickedPhoto?>(null)
    private val placePrompt = MutableStateFlow<PlacePrompt?>(null)
    private val placeQuery = MutableStateFlow("")

    /**
     * D68. The bundled place list, read once the first time anything collects this — which is
     * the first time the screen is opened, not process start: most registrations never raise
     * the prompt at all (D63), and 900 KB of asset should not be parsed for them.
     */
    private val gazetteerPlaces = flow { emit(gazetteer.places()) }.onStart { emit(emptyList()) }
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
            preselectedSpeciesId = preselectedSpeciesId,
            placePrompt = placePrompt,
            place = placeSearchState(
                query = placeQuery,
                gazetteer = gazetteerPlaces,
                recent = repository.placeLabels(),
            ).flowOn(Dispatchers.Default),
        ),
        _grantWarning,
    ) { state, warning ->
        state.copy(grantWarning = warning)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RegisterUiState(),
        )

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** D68: each keystroke in the place prompt, which re-ranks the suggestions. */
    fun onPlaceQueryChange(value: String) {
        placeQuery.value = value
    }

    /**
     * D64. The prompt's answer: a typed place resumes whichever tap raised it; a dismissal
     * just closes it and the screen is as it was. The screen only offers this for a place off
     * the list (D68), and the check is repeated here because this is the one entry point.
     */
    fun onPlaceEntered(place: String) {
        val label = placeLabelOrNull(place) ?: return
        if (!uiState.value.place.isKnown) return
        val prompt = placePrompt.value ?: return
        placePrompt.value = null
        placeQuery.value = ""
        when (prompt) {
            PlacePrompt.REGISTER -> register(label)
            PlacePrompt.ADD_OWN -> viewModelScope.launch {
                sendAddOwn(typedName = query.value.trim(), prefetched = null, place = label)
            }
        }
    }

    fun onPlacePromptDismissed() {
        placePrompt.value = null
        placeQuery.value = ""
    }

    fun onSelectSpecies(speciesId: String) {
        selectedSpeciesId.value = if (selectedSpeciesId.value == speciesId) null else speciesId
        error.value = null
    }

    /**
     * Called with the picker's result after the route has taken the persistable grant. D60:
     * the EXIF is read here, off the main thread, so the place field knows whether it is
     * required before the user reaches the button; the read lands only if this is still the
     * photo on screen.
     */
    fun onPhotoPicked(picked: PickedPhoto?) {
        photo.value = picked
        error.value = null
        if (picked == null) return
        refreshGrantPressure()
        viewModelScope.launch {
            val facts = withContext(Dispatchers.IO) { photos.readExif(picked.uri) }
            val located = facts.lat != null && facts.lng != null
            photo.update { current ->
                if (current?.uri == picked.uri) current.copy(hasLocation = located) else current
            }
        }
    }

    /**
     * M08's typed path, routed through the same hand-off. D64: a photo with no coordinates
     * raises the place prompt first, and the answer lands in [onPlaceEntered].
     */
    fun onAddOwnTyped() {
        val state = uiState.value
        if (!state.canAddOwn) return
        if (state.needsPlacePrompt) {
            placePrompt.value = PlacePrompt.ADD_OWN
            return
        }
        viewModelScope.launch { sendAddOwn(typedName = query.value.trim(), prefetched = null, place = null) }
    }

    /**
     * The one place a photo leaves this screen for the add-your-own card.
     *
     * **A camera shot is handed over still sitting in the cache, and its source travels with
     * it.** Promoting here is tempting — a cache URI registered as a capture would be deleted
     * by the next cold start's sweep, leaving a capture whose photo the app itself destroyed —
     * but the card may still be abandoned, so the card promotes before it registers and
     * sweeps the cache either way. The draft holder is in memory, so a draft and its cache
     * file die together on process death; nothing dangles.
     */
    private suspend fun sendAddOwn(typedName: String, prefetched: LookupOutcome?, place: String?) {
        val picked = photo.value ?: return
        events.send(
            RegisterEvent.AddOwnSpecies(
                typedName,
                picked.uri,
                picked.source,
                prefetched,
                place = place,
            ),
        )
    }

    /**
     * D64. The Register tap: a photo that carries its own coordinates registers at once; one
     * that does not raises the "Where was this?" prompt and waits for [onPlaceEntered].
     */
    fun onRegister() {
        val state = uiState.value
        if (!state.canRegister) return
        if (state.needsPlacePrompt) {
            placePrompt.value = PlacePrompt.REGISTER
            return
        }
        register(locationLabel = null)
    }

    private fun register(locationLabel: String?) {
        val speciesId = selectedSpeciesId.value ?: return
        val picked = photo.value ?: return
        if (registering.value) return
        registering.value = true
        viewModelScope.launch {
            // D26: a camera shot lives in app cache until this moment. It is promoted into the
            // gallery now, so the user finds it where every other photo of theirs is.
            val registerUri = if (shouldPromoteToGallery(picked.source)) {
                withContext(Dispatchers.IO) {
                    photos.promoteToGallery(picked.uri, picked.displayName ?: "BioDex.jpg")
                } ?: picked.uri
            } else {
                picked.uri
            }

            val result = registrar.register(
                speciesId,
                registerUri,
                locationLabel = locationLabel,
                // D60: the place gate above read the cache file; the door reads the same one.
                exifUri = picked.uri.takeIf { it != registerUri },
            )
            when (result) {
                is CaptureRegistrar.RegisterResult.Registered -> {
                    if (shouldDeleteCacheFile(picked.source)) {
                        withContext(Dispatchers.IO) { photos.sweepCameraCache() }
                    }
                    events.send(RegisterEvent.Registered(result.speciesId, result.isFirst))
                }

                is CaptureRegistrar.RegisterResult.ThumbnailFailed -> {
                    error.value = "That photo could not be read. Pick another one — nothing " +
                        "was saved."
                    events.send(RegisterEvent.PhotoUnreadable)
                }

                // The tap prompts before it gets here, so this is belt to that braces: the
                // door's own rule (D60), and the prompt is raised again rather than an error
                // shown.
                CaptureRegistrar.RegisterResult.PlaceMissing -> {
                    placePrompt.value = PlacePrompt.REGISTER
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
                    photos = container.photoGateway,
                    gazetteer = container.placeGazetteer,
                )
            }
        }
    }
}
