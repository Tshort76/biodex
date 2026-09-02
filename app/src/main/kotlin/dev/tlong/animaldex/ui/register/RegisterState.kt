package dev.tlong.animaldex.ui.register

import dev.tlong.animaldex.domain.SpeciesSummary
import dev.tlong.animaldex.ui.grid.matchesQuery
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
)

/** M09's outcome, raised to the screen as a one-shot event so the route can navigate. */
sealed interface RegisterEvent {
    /** [isFirst] decides between the unlock reveal and the low-key "+1" (DESIGN.md §4). */
    data class Registered(val speciesId: String, val isFirst: Boolean) : RegisterEvent

    data object PhotoUnreadable : RegisterEvent
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
) {
    val canRegister: Boolean get() = selected != null && photo != null && !registering

    /**
     * M08's affordance. The name is not in the catalogue, so "Add your own species" is the
     * only way forward — slice 7 makes it work.
     */
    val noResults: Boolean get() = query.isNotBlank() && results.isEmpty()

    val registerLabel: String
        get() = selected?.let { "Register — ${it.commonName}" } ?: "Register"
}

/**
 * How many rows the results list shows. The mockup's list is short and the screen has a photo
 * row and two buttons under it; an unbounded list of 120 would bury them.
 */
const val REGISTER_RESULT_LIMIT = 25

/**
 * Search is the grid's, reused verbatim (M07/M14 use the same rule) and offline by
 * construction — it runs over rows Room already gave us. An empty query lists the catalogue
 * in dex order rather than showing nothing, so a preselected species is visible in context.
 */
internal fun registerResults(species: List<SpeciesSummary>, query: String): List<SpeciesSummary> =
    species.filter { matchesQuery(it, query) }
        .sortedBy { it.dexNumber }
        .take(REGISTER_RESULT_LIMIT)

fun registerUiState(
    species: Flow<List<SpeciesSummary>>,
    query: Flow<String>,
    selectedSpeciesId: Flow<String?>,
    photo: Flow<PickedPhoto?>,
    registering: Flow<Boolean>,
    error: Flow<String?>,
): Flow<RegisterUiState> =
    combine(species, query, selectedSpeciesId, photo, registering) { all, q, id, pic, busy ->
        RegisterUiState(
            query = q,
            results = registerResults(all, q),
            // Resolved against the whole catalogue, not the visible results: a selection made
            // before typing must survive a query that filters it out of view.
            selected = all.firstOrNull { it.id == id },
            photo = pic,
            registering = busy,
        )
    }.combine(error) { state, message -> state.copy(error = message) }
