package dev.tlong.biodex.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.net.SpeciesLookupRepository
import dev.tlong.biodex.data.photo.CaptureRegistrar
import dev.tlong.biodex.data.photo.PhotoGateway
import dev.tlong.biodex.data.place.PlaceGazetteer
import dev.tlong.biodex.data.repo.DexRepository
import dev.tlong.biodex.domain.PlaceAnswer
import dev.tlong.biodex.media.NetworkMonitor
import dev.tlong.biodex.ui.addspecies.AddSpeciesDraftHolder
import dev.tlong.biodex.ui.register.PickedPhoto
import dev.tlong.biodex.ui.register.PlaceSearchState
import dev.tlong.biodex.ui.register.placeAnswerFor
import dev.tlong.biodex.ui.register.placeSearchState
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

/** D76: what a Capture! from the entry came to. */
sealed interface CaptureEvent {
    /** Recorded. [isFirst] plays the unlock reveal; a repeat gets the "+1 photo" chip (M09). */
    data class Captured(val isFirst: Boolean) : CaptureEvent

    /** The photo could not be read into a thumbnail; nothing was saved. */
    data object Unreadable : CaptureEvent
}

class EntryDetailViewModel(
    private val repository: DexRepository,
    private val networkMonitor: NetworkMonitor,
    private val lookups: SpeciesLookupRepository,
    private val drafts: AddSpeciesDraftHolder,
    private val speciesId: String,
    private val registrar: CaptureRegistrar,
    private val photos: PhotoGateway,
    private val gazetteer: PlaceGazetteer = PlaceGazetteer.None,
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

    // -----------------------------------------------------------------------
    // D76. Capture! — the Register screen's pick, place and write, for this one species.
    // -----------------------------------------------------------------------

    /** A photo that carried no place, waiting on the "Where was this?" prompt. */
    private val awaitingPlace = MutableStateFlow<PickedPhoto?>(null)
    private val placeQuery = MutableStateFlow("")
    private var capturing = false
    private val captures = Channel<CaptureEvent>(Channel.BUFFERED)
    val captureEvents = captures.receiveAsFlow()

    /** The prompt's suggestions while it is up, and null while it is not (D64, D68). */
    val placePrompt: StateFlow<PlaceSearchState?> = combine(
        awaitingPlace,
        placeSearchState(
            query = placeQuery,
            // Read the first time the prompt could be shown, not when the entry opens.
            gazetteer = flow { emit(gazetteer.places()) }.onStart { emit(emptyList()) },
            recent = repository.placeLabels(),
        ).flowOn(Dispatchers.Default),
    ) { waiting, search -> search.takeIf { waiting != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The picker's answer. A photo whose EXIF carries coordinates is recorded at once; one that
     * does not raises the prompt, exactly as the Register tap does (D64).
     */
    fun onPhotoPicked(picked: PickedPhoto) {
        if (capturing) return
        capturing = true
        viewModelScope.launch {
            val facts = withContext(Dispatchers.IO) { photos.readExif(picked.uri) }
            if (facts.lat != null && facts.lng != null) {
                record(picked, place = null)
            } else {
                awaitingPlace.value = picked
                capturing = false
            }
        }
    }

    fun onPlaceQueryChange(value: String) {
        placeQuery.value = value
    }

    /** The list's spelling and point when it holds what was typed, the text as typed if not. */
    fun onPlaceEntered(typed: String) {
        val answer = placeAnswerFor(typed, placePrompt.value ?: return) ?: return
        val picked = awaitingPlace.value ?: return
        awaitingPlace.value = null
        placeQuery.value = ""
        capturing = true
        viewModelScope.launch { record(picked, answer) }
    }

    /** Cancel: nothing is written, and the grant the pick took is handed back unless in use. */
    fun onPlacePromptDismissed() {
        val picked = awaitingPlace.value ?: return
        awaitingPlace.value = null
        placeQuery.value = ""
        viewModelScope.launch {
            if (repository.captureCountForUri(picked.uri) == 0) photos.releaseGrant(picked.uri)
        }
    }

    private suspend fun record(picked: PickedPhoto, place: PlaceAnswer?) {
        val result = registrar.register(
            speciesId,
            picked.uri,
            locationLabel = place?.label,
            placeLat = place?.lat,
            placeLng = place?.lng,
        )
        when (result) {
            is CaptureRegistrar.RegisterResult.Registered ->
                captures.send(CaptureEvent.Captured(result.isFirst))
            is CaptureRegistrar.RegisterResult.ThumbnailFailed -> captures.send(CaptureEvent.Unreadable)
            // The door's own rule (D60) disagreeing with the EXIF read: ask, rather than fail.
            CaptureRegistrar.RegisterResult.PlaceMissing -> awaitingPlace.value = picked
        }
        capturing = false
    }

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
                        registrar = container.captureRegistrar,
                        photos = container.photoGateway,
                        gazetteer = container.placeGazetteer,
                    )
                }
            }
    }
}
