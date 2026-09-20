package dev.tlong.biodex.ui.register

import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.data.photo.PhotoSourceKind
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.ui.grid.matchesQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

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
    /**
     * D60. Whether the photograph's EXIF carries a coordinate pair — read on pick, off the
     * main thread, so the screen can say "place from photo" or ask for one *before* the button
     * is pressed. Null while the read is still running; the button waits for the answer.
     */
    val hasLocation: Boolean? = null,
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
        val photoSource: PhotoSourceKind,
        val prefetched: LookupOutcome? = null,
        /** D56: what the user typed under "Where?", carried to the capture the card writes. */
        val place: String? = null,
    ) : RegisterEvent
}

data class RegisterUiState(
    val query: String = "",
    val results: List<SpeciesSummary> = emptyList(),
    val selected: SpeciesSummary? = null,
    val photo: PickedPhoto? = null,
    /**
     * D56, required since D60. Where the catch happened, in the user's words. A photo that
     * still carries GPS names its own place; this field is the answer for the far more common
     * photo that does not — the system picker strips location from most of what it hands over
     * (R3) — and one of the two must hold before anything is written.
     */
    val place: String = "",
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
) {
    /**
     * D60: a sighting is a time and a place, so the place is satisfied either by the photo's
     * own coordinates or by what the user typed. The same rule is enforced at the door in
     * `CaptureRegistrar`; this copy exists so the button can explain itself.
     */
    val placeSatisfied: Boolean
        get() = placeLabelOrNull(place) != null || photo?.hasLocation == true

    /**
     * The line under the place field. It changes with the photo rather than the typing: once a
     * photo carries its own coordinates the field is genuinely optional again.
     */
    val placeHint: PlaceHint
        get() = when {
            photo == null -> PlaceHint.REQUIRED
            photo.hasLocation == null -> PlaceHint.READING
            photo.hasLocation -> PlaceHint.FROM_PHOTO
            else -> PlaceHint.REQUIRED
        }

    /**
     * Every kingdom needs a photograph: for an animal or a fungus the photograph *is* the
     * catch (M07) — though it can be unlinked afterwards and the sighting kept (D61). The
     * plant exception (M41) left with the plants (D59). And every capture needs a place (D60).
     */
    val canRegister: Boolean
        get() = selected != null && !registering && photo != null && placeSatisfied

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
    val canAddOwn: Boolean
        get() = query.isNotBlank() && photo != null && placeSatisfied && !registering

    val addOwnLabel: String
        get() = when {
            query.isBlank() -> "Not in the list? Type a name to add your own species ＋"
            photo == null -> "Attach a photo to add “${query.trim()}” as your own species ＋"
            !placeSatisfied -> "Say where to add “${query.trim()}” as your own species ＋"
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
    place: Flow<String> = flowOf(""),
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
        .combine(place) { state, where -> state.copy(place = where) }

/** D60: what the place field says under itself, decided by the photo, not the typing. */
enum class PlaceHint(val placeholder: String, val note: String?) {
    /** No photo yet, or a photo with no coordinates: the field is the only place there is. */
    REQUIRED("Where was this? (required)", null),

    /** The EXIF read is still running; a beat at most. */
    READING("Where was this?", "Checking the photo for its location…"),

    /** The photo carries GPS: typing is optional and, if done, wins (D56). */
    FROM_PHOTO("Where was this? (optional)", "Place read from the photo ✓"),
}

/** D56: the typed place, trimmed, or null when nothing was typed — never an empty label. */
internal fun placeLabelOrNull(place: String): String? = place.trim().takeIf { it.isNotEmpty() }
