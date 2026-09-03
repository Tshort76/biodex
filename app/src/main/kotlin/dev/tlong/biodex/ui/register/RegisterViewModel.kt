package dev.tlong.biodex.ui.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import dev.tlong.biodex.AppContainer
import dev.tlong.biodex.data.identify.CandidateResolver
import dev.tlong.biodex.data.identify.IdentifierRegistry
import dev.tlong.biodex.data.identify.ResolvedCandidate
import dev.tlong.biodex.data.identify.UploadImage
import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.net.LookupResult
import dev.tlong.biodex.data.net.SpeciesLookupRepository
import dev.tlong.biodex.data.photo.CaptureRegistrar
import dev.tlong.biodex.data.photo.GrantPressure
import dev.tlong.biodex.data.photo.PhotoGateway
import dev.tlong.biodex.data.photo.shouldDeleteCacheFile
import dev.tlong.biodex.data.photo.shouldPromoteToGallery
import dev.tlong.biodex.data.repo.DexRepository
import dev.tlong.biodex.data.settings.AppSettings
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.media.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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
    private val identifiers: IdentifierRegistry,
    private val resolver: CandidateResolver,
    private val lookups: SpeciesLookupRepository,
    private val photos: PhotoGateway,
    private val settings: AppSettings,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedSpeciesId = MutableStateFlow(preselectedSpeciesId)
    private val photo = MutableStateFlow<PickedPhoto?>(null)
    private val registering = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val events = Channel<RegisterEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val _grantWarning = MutableStateFlow<String?>(null)

    private val identification = MutableStateFlow<IdentificationState>(IdentificationState.Idle)

    /**
     * The three things the Identify button's enabled-ness turns on, as one value so the state
     * function stays a pure projection. The key and the count are re-read whenever the button
     * could be pressed rather than captured once: pasting a key in Settings has to take effect
     * without restarting the app (D24).
     */
    private val identifyGates = MutableStateFlow(identifyGatesNow())

    val uiState: StateFlow<RegisterUiState> = combine(
        registerUiState(
            species = repository.speciesSummaries(),
            query = query,
            selectedSpeciesId = selectedSpeciesId,
            photo = photo,
            registering = registering,
            error = error,
            preselectedSpeciesId = preselectedSpeciesId,
        ),
        _grantWarning,
        identification,
        identifyGates,
        networkMonitor.online,
    ) { state, warning, identifyState, gates, online ->
        state.copy(
            grantWarning = warning,
            identification = identifyState,
            identifiableKingdoms = identifiableKingdoms,
            identifyProviderName = identifyProviderName,
            online = online,
            hasIdentifyKey = gates.hasKey,
            identificationsUsed = gates.used,
            identificationCap = gates.cap,
        )
    }
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
        // §5.2 rule 9: no result cache. A new photo's candidates are that photo's, so the
        // previous one's panel goes rather than lingering above an unrelated picture.
        identification.value = IdentificationState.Idle
        identifyGates.value = identifyGatesNow()
        if (picked != null) refreshGrantPressure()
    }

    /** The panel is dismissible (§5.2 rule 1); the photo and the selection stay. */
    fun onDismissIdentification() {
        identification.value = IdentificationState.Idle
    }

    /**
     * M31/M36. The only network write of a photo this app makes, and it happens on this press
     * and nowhere else — never on attach, never on capture, never in the background.
     */
    fun onIdentify() {
        val picked = photo.value ?: return
        val kingdom = identifyContextKingdom(uiState.value.selected?.kingdom)
        val identifier = identifiers[kingdom] ?: return
        if (identification.value is IdentificationState.Running) return
        if (!uiState.value.canIdentify) return

        identification.value = IdentificationState.Running(identifier.providerName)
        viewModelScope.launch {
            // The decode-and-re-encode is the expensive half and the privacy-relevant half
            // (M36); it belongs off the main thread and before anything touches the network.
            val bytes = withContext(Dispatchers.IO) { photos.readForUpload(picked.uri) }
            if (bytes == null) {
                identification.value =
                    IdentificationState.Failed("That photo could not be read.")
                return@launch
            }

            when (val raw = identifier.identify(UploadImage(bytes), kingdom)) {
                is LookupResult.Found -> {
                    // M37 counts a *successful upload*: the service answered, so the quota
                    // this cap is protecting has actually been spent.
                    settings.recordIdentification()
                    identifyGates.value = identifyGatesNow()
                    resolveAndShow(identifier.providerName, identifier.scoreKind, raw.value, kingdom)
                }

                LookupResult.NotFound -> {
                    settings.recordIdentification()
                    identifyGates.value = identifyGatesNow()
                    identification.value =
                        IdentificationState.NoCandidates(identifier.providerName)
                }

                is LookupResult.Failed ->
                    identification.value = IdentificationState.Failed(raw.reason)
            }
        }
    }

    private suspend fun resolveAndShow(
        provider: String,
        scoreKind: dev.tlong.biodex.data.identify.ScoreKind,
        candidates: List<dev.tlong.biodex.data.identify.IdCandidate>,
        kingdom: Kingdom,
    ) {
        val catalogue = repository.speciesSummaries().first()
        identification.value = when (
            val resolved = resolver.resolve(candidates, catalogue, kingdom)
        ) {
            is LookupResult.Found -> IdentificationState.Done(
                provider = provider,
                scoreKind = scoreKind,
                candidates = resolved.value.candidates,
                dropped = resolved.value.dropped,
            )

            // Every name the service offered was dropped by M32. From the user's side that is
            // indistinguishable from the service recognising nothing, and it is the same calm
            // outcome — the app could ask, and the answer was nothing it will stand behind.
            LookupResult.NotFound -> IdentificationState.NoCandidates(provider)

            is LookupResult.Failed -> IdentificationState.Failed(resolved.reason)
        }
    }

    /**
     * D20: picking is the user's, always. A candidate already in the catalogue selects exactly
     * as tapping its row in the list would — registration never learns that identification
     * happened (§5.2 rule 4).
     */
    fun onPickCandidate(candidate: ResolvedCandidate) {
        val speciesId = candidate.catalogueSpeciesId
        if (speciesId != null) {
            selectedSpeciesId.value = speciesId
            error.value = null
            return
        }
        openAddYourOwn(candidate)
    }

    /**
     * M33's other half: a validated name the catalogue does not have goes to the **existing**
     * confirmation card (M19) with the GBIF lookup already done, so there is one confirmation
     * path in the app rather than two. `AddSpeciesDraft.prefetched` is the slot the backfill
     * case already uses for exactly this.
     */
    private fun openAddYourOwn(candidate: ResolvedCandidate) {
        val picked = photo.value ?: return
        viewModelScope.launch {
            val details = lookups.detailsFor(candidate.gbif.best, candidate.scientificName)
            events.send(
                RegisterEvent.AddOwnSpecies(
                    typedName = candidate.scientificName,
                    photoUri = picked.uri,
                    prefetched = LookupOutcome.Resolved(
                        candidates = candidate.gbif.candidates,
                        selectedIndex = 0,
                        details = details,
                    ),
                ),
            )
        }
    }

    fun onRegister() {
        val speciesId = selectedSpeciesId.value ?: return
        val picked = photo.value ?: return
        val kingdom = uiState.value.selected?.kingdom ?: Kingdom.ANIMAL
        if (registering.value) return
        registering.value = true
        viewModelScope.launch {
            // D26: a camera shot lives in app cache until this moment. A kingdom that keeps
            // its photo gets it promoted into the gallery now, so the user finds it where
            // every other photo of theirs is; a plant's is never promoted and is swept below,
            // which is what keeps it out of the gallery entirely (M41).
            val registerUri = if (shouldPromoteToGallery(picked.source, kingdom)) {
                withContext(Dispatchers.IO) {
                    photos.promoteToGallery(picked.uri, picked.displayName ?: "BioDex.jpg")
                } ?: picked.uri
            } else {
                picked.uri
            }

            when (val result = registrar.register(speciesId, registerUri)) {
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

    private val identifiableKingdoms: Set<Kingdom> =
        Kingdom.entries.filter(identifiers::supports).toSet()

    private val identifyProviderName: String =
        identifiers[Kingdom.PLANT]?.providerName.orEmpty()

    private fun identifyGatesNow() = IdentifyGates(
        hasKey = settings.plantNetKeyNow() != null,
        used = settings.identificationsUsedNow(),
        cap = settings.identificationCapNow(),
    )

    private data class IdentifyGates(val hasKey: Boolean, val used: Int, val cap: Int)

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
                    identifiers = container.identifiers,
                    resolver = container.candidateResolver,
                    lookups = container.speciesLookupRepository,
                    photos = container.photoGateway,
                    settings = container.settings,
                    networkMonitor = container.networkMonitor,
                )
            }
        }
    }
}
