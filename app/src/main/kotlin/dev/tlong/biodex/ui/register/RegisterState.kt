package dev.tlong.biodex.ui.register

import dev.tlong.biodex.data.identify.DEFAULT_MONTHLY_IDENTIFICATION_CAP
import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.photo.PhotoSourceKind
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.ui.grid.matchesQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Frame 3 of `mockup.html` (M07). Like the grid and the detail screen, the whole composition
 * is a top-level pure function over cold flows, so the JVM suite can pin what the screen does
 * with no device (ARCHITECTURE.md 6.2, 6.5).
 */

/** The photo the user attached, before anything has been written. */
data class PickedPhoto(
    val uri: String,
    val displayName: String? = null,
    /**
     * M40/D26. Where it came from decides two things the URI string cannot: whether it must be
     * promoted into the gallery at registration, and whether its cache file must be swept
     * afterwards. A `FileProvider` URI and a picker URI are both `content://`.
     */
    val source: PhotoSourceKind = PhotoSourceKind.GALLERY_PICKER,
)

/** M09's outcome, raised to the screen as a one-shot event so the route can navigate. */
sealed interface RegisterEvent {
    /** [isFirst] decides between the unlock reveal and the low-key "+1" (DESIGN.md §4). */
    data class Registered(val speciesId: String, val isFirst: Boolean) : RegisterEvent

    data object PhotoUnreadable : RegisterEvent

    /**
     * M33's not-in-dex hand-off. It travels as an event rather than a direct call because the
     * draft and the navigation belong to the route, and it carries [prefetched] so the
     * existing confirmation card (M19) opens with the GBIF lookup already done rather than
     * repeating it — there is one confirmation path in this app, not two.
     */
    data class AddOwnSpecies(
        val typedName: String,
        val photoUri: String,
        val prefetched: LookupOutcome? = null,
    ) : RegisterEvent
}

data class RegisterUiState(
    val query: String = "",
    val results: List<SpeciesSummary> = emptyList(),
    val selected: SpeciesSummary? = null,
    val photo: PickedPhoto? = null,
    val registering: Boolean = false,
    val error: String? = null,
    /** 4.4: shown only when the persisted-grant count is actually near Android's cap. */
    val grantWarning: String? = null,
    /**
     * D18. Where the species the screen was *opened for* sits in [results], so the list can be
     * scrolled to it once on arrival. It is the route's `preselectedSpeciesId` rather than
     * [selected]: a row the user taps themselves is already under their thumb, and scrolling
     * to it would be the list jumping for no reason.
     */
    val preselectedIndex: Int? = null,

    // Identification (M31, M37, M38). Everything the button and the panel need, and nothing
    // that is not pure: the registry, the settings and the network monitor are read into these
    // fields by the ViewModel so the screen's whole behaviour stays JVM-testable.

    val identification: IdentificationState = IdentificationState.Idle,
    /** Kingdoms the registry has a provider for — `PLANT` alone in v3 (D19). */
    val identifiableKingdoms: Set<Kingdom> = emptySet(),
    val identifyProviderName: String = "",
    val online: Boolean = true,
    val hasIdentifyKey: Boolean = false,
    val identificationsUsed: Int = 0,
    val identificationCap: Int = DEFAULT_MONTHLY_IDENTIFICATION_CAP,
) {
    val canRegister: Boolean get() = selected != null && photo != null && !registering

    // -----------------------------------------------------------------------
    // The Identify action (§5.1). Hidden and disabled are different answers to
    // different questions: hidden means "there is no provider for this", which
    // can never change on this screen, and disabled-with-a-reason means "not
    // right now, and here is what to do about it" (D19, M38).
    // -----------------------------------------------------------------------

    /**
     * Hidden rather than disabled for an animal or a fungus, because a disabled button on a
     * screen where it can never become enabled is just clutter (§3.3). It also waits for a
     * photo: with nothing attached there is nothing to identify, and the photo row already
     * says so.
     */
    val identifyVisible: Boolean
        get() = photo != null &&
            identifyContextKingdom(selected?.kingdom) in identifiableKingdoms

    val identifyDisabledReason: String?
        get() = identifyDisabledReason(
            provider = identifyProviderName,
            online = online,
            hasKey = hasIdentifyKey,
            identificationsUsed = identificationsUsed,
            cap = identificationCap,
            running = identification is IdentificationState.Running,
        )

    val canIdentify: Boolean get() = identifyVisible && identifyDisabledReason == null

    val identifyLabel: String get() = "Identify with $identifyProviderName ↑"

    /**
     * M08's affordance. The name is not in the catalogue, so "Add your own species" is the
     * only way forward — slice 7 makes it work.
     */
    val noResults: Boolean get() = query.isNotBlank() && results.isEmpty()

    val registerLabel: String
        get() = selected?.let { "Register — ${it.commonName}" } ?: "Register"

    /**
     * M08 into M18–M21. The flow needs the two things only the user has: a name that is not in
     * the catalogue, and the photo. Offered as soon as a name is typed — the button explains
     * what it still wants rather than disappearing.
     */
    val canAddOwn: Boolean get() = query.isNotBlank() && photo != null && !registering

    val addOwnLabel: String
        get() = when {
            query.isBlank() -> "Not in the list? Type a name to add your own species ＋"
            photo == null -> "Attach a photo to add “${query.trim()}” as your own species ＋"
            else -> "Add “${query.trim()}” as your own species ＋"
        }
}

/**
 * Search is the grid's, reused verbatim (M07/M14 use the same rule) and offline by
 * construction — it runs over rows Room already gave us. An empty query lists the catalogue
 * in dex order rather than showing nothing, so a preselected species is visible in context.
 *
 * Uncapped (11.4, D18). The old 25-row cap existed only to keep the photo row and the buttons
 * reachable inside one long scroll; the screen now pins them, and the list is a `LazyColumn`,
 * so all 200 species are listed.
 */
internal fun registerResults(species: List<SpeciesSummary>, query: String): List<SpeciesSummary> =
    species.filter { matchesQuery(it, query) }
        .sortedBy { it.dexNumber }

fun registerUiState(
    species: Flow<List<SpeciesSummary>>,
    query: Flow<String>,
    selectedSpeciesId: Flow<String?>,
    photo: Flow<PickedPhoto?>,
    registering: Flow<Boolean>,
    error: Flow<String?>,
    preselectedSpeciesId: String? = null,
): Flow<RegisterUiState> =
    combine(species, query, selectedSpeciesId, photo, registering) { all, q, id, pic, busy ->
        val results = registerResults(all, q)
        RegisterUiState(
            query = q,
            results = results,
            // Resolved against the whole catalogue, not the visible results: a selection made
            // before typing must survive a query that filters it out of view.
            selected = all.firstOrNull { it.id == id },
            photo = pic,
            registering = busy,
            preselectedIndex = preselectedSpeciesId
                ?.let { wanted -> results.indexOfFirst { it.id == wanted } }
                ?.takeIf { it >= 0 },
        )
    }.combine(error) { state, message -> state.copy(error = message) }
