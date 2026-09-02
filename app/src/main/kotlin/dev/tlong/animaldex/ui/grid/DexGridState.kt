package dev.tlong.animaldex.ui.grid

import dev.tlong.animaldex.domain.DexProgress
import dev.tlong.animaldex.domain.Ecosystem
import dev.tlong.animaldex.domain.SpeciesSummary
import dev.tlong.animaldex.domain.TaxClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The grid's search and filter logic (M14), kept as pure functions over plain lists and cold
 * flows. ARCHITECTURE.md 6.2 puts this in the ViewModel rather than in SQL; splitting the
 * composition out of the ViewModel class is what lets the JVM suite exercise it with no
 * device, no Main dispatcher and no Room — the fake "repository" is three MutableStateFlows.
 */

/** DESIGN.md M14's caught/uncaught filter. Single-select; `ALL` is the mockup's "All" chip. */
enum class CaughtFilter { ALL, CAUGHT, UNCAUGHT }

/**
 * Single-select within each dimension, AND across dimensions and with the search query —
 * the mockup's chip row reads as three independent narrowings, not as one radio group.
 */
data class DexGridFilters(
    val caught: CaughtFilter = CaughtFilter.ALL,
    val taxClass: TaxClass? = null,
    val ecosystemId: String? = null,
) {
    val isEmpty: Boolean
        get() = caught == CaughtFilter.ALL && taxClass == null && ecosystemId == null
}

data class DexGridUiState(
    val regionLabel: String = "",
    val caughtCount: Int = 0,
    val totalCount: Int = 0,
    val query: String = "",
    val filters: DexGridFilters = DexGridFilters(),
    val ecosystems: List<Ecosystem> = emptyList(),
    val species: List<SpeciesSummary> = emptyList(),
    /** True only before the catalogue import has produced anything to show (3.3). */
    val loading: Boolean = true,
) {
    val isFiltered: Boolean get() = query.isNotBlank() || !filters.isEmpty
}

/** Case-insensitive substring over common and scientific name (M14). */
internal fun matchesQuery(species: SpeciesSummary, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return species.commonName.contains(q, ignoreCase = true) ||
        species.scientificName?.contains(q, ignoreCase = true) == true
}

internal fun matchesFilters(species: SpeciesSummary, filters: DexGridFilters): Boolean {
    val caughtOk = when (filters.caught) {
        CaughtFilter.ALL -> true
        CaughtFilter.CAUGHT -> species.caught
        CaughtFilter.UNCAUGHT -> !species.caught
    }
    val classOk = filters.taxClass == null || species.taxClass == filters.taxClass
    val ecoOk = filters.ecosystemId == null || filters.ecosystemId in species.ecosystemIds
    return caughtOk && classOk && ecoOk
}

/**
 * Search and filters compose: a species survives only if it satisfies every active narrowing.
 * Dex order is the grid's order (M01), and user-added species trail the catalogue because
 * their dex numbers start above [dev.tlong.animaldex.domain.USER_DEX_NUMBER_BASE] (M02).
 */
fun filterSpecies(
    species: List<SpeciesSummary>,
    query: String,
    filters: DexGridFilters,
): List<SpeciesSummary> = species
    .filter { matchesQuery(it, query) && matchesFilters(it, filters) }
    .sortedBy { it.dexNumber }

/**
 * The whole screen state as one cold flow over the repository's reads plus the two pieces of
 * user input. The ViewModel is this function plus `stateIn`; tests call it directly.
 */
fun dexGridUiState(
    species: Flow<List<SpeciesSummary>>,
    ecosystems: Flow<List<Ecosystem>>,
    progress: Flow<DexProgress>,
    query: Flow<String>,
    filters: Flow<DexGridFilters>,
    regionLabel: (String) -> String = ::regionLabelFor,
): Flow<DexGridUiState> =
    combine(species, ecosystems, progress, query, filters) { all, ecos, prog, q, f ->
        DexGridUiState(
            regionLabel = regionLabel(prog.regionId),
            caughtCount = prog.caughtCount,
            totalCount = prog.totalSpecies,
            query = q,
            filters = f,
            ecosystems = ecos,
            species = filterSpecies(all, q, f),
            loading = all.isEmpty() && prog.totalSpecies == 0,
        )
    }

/**
 * The region's display name (M01's header). The catalogue asset carries `regionName` but
 * slice 3 does not import it into Room, and this slice does not touch the schema — see
 * ARCHITECTURE.md 6.5. One region ships in v1; C03 turns this into a table read.
 */
fun regionLabelFor(regionId: String): String = when (regionId) {
    "pacific" -> "Pacific"
    "" -> ""
    else -> regionId.replaceFirstChar { it.uppercase() }
}

/** The label the class chips show; the mockup uses the plural common word, not the enum. */
fun TaxClass.chipLabel(): String = when (this) {
    TaxClass.BIRD -> "Birds"
    TaxClass.MAMMAL -> "Mammals"
    TaxClass.REPTILE -> "Reptiles"
    TaxClass.AMPHIBIAN -> "Amphibians"
    TaxClass.FISH -> "Fish"
    TaxClass.INSECT -> "Insects"
    TaxClass.OTHER_INVERTEBRATE -> "Invertebrates"
}
