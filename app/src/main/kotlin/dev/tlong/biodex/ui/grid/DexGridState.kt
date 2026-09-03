package dev.tlong.biodex.ui.grid

import dev.tlong.biodex.domain.DexProgress
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.Meter
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
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
    /** M23's kingdom chips. Null is "both", which is what the `All` chip restores. */
    val kingdom: Kingdom? = null,
    /** M23's use chips. A use never matches an animal, whose `uses` is always empty. */
    val use: PlantUse? = null,
    val taxClass: TaxClass? = null,
    val ecosystemId: String? = null,
) {
    val isEmpty: Boolean
        get() = caught == CaughtFilter.ALL &&
            kingdom == null &&
            use == null &&
            taxClass == null &&
            ecosystemId == null
}

data class DexGridUiState(
    /** The region's own name, read from the `regions` table — "Pacific USA" (11.1). */
    val regionLabel: String = "",
    /** One pill per kingdom. A kingdom with nothing in it hides rather than reading `0/0`. */
    val animals: Meter = Meter(0, 0, 0),
    val plants: Meter = Meter(0, 0, 0),
    val fungi: Meter = Meter(0, 0, 0),
    val query: String = "",
    val filters: DexGridFilters = DexGridFilters(),
    val ecosystems: List<Ecosystem> = emptyList(),
    val species: List<SpeciesSummary> = emptyList(),
    /**
     * The classes the region actually holds, from the same `perClass` breakdown Stats reads
     * (6.3) — which carries a class only when some species has it. It is what stops the chip
     * row offering Trees / Shrubs / Herbs / Ferns against a catalogue with no plants in it.
     */
    val availableClasses: Set<TaxClass> = emptySet(),
    /** True only before the catalogue import has produced anything to show (3.3). */
    val loading: Boolean = true,
) {
    val isFiltered: Boolean get() = query.isNotBlank() || !filters.isEmpty

    /** M29: a plant pill on a dex with no plants in it would only ever read `0/0`. */
    val showPlantPill: Boolean get() = plants.total > 0

    /** The same rule for fungi, which arrived as a kingdom before they arrived in the asset. */
    val showFungiPill: Boolean get() = fungi.total > 0

    /**
     * The kingdoms the region actually holds, derived from [availableClasses] because every
     * class knows its kingdom and the breakdown already only carries a class some species has.
     *
     * This is what stops a `Fungi` chip appearing over a catalogue with no fungi in it — the
     * same bug slice 9 left for `Trees` / `Shrubs`, which arrived again the moment a third
     * kingdom existed in the enum but not yet in the asset.
     */
    val availableKingdoms: Set<Kingdom>
        get() = availableClasses.mapTo(mutableSetOf()) { it.kingdom }

    /**
     * One kingdom is no choice at all: `Animals` over an all-animal dex can only ever match
     * everything, so the row is offered only once there is something to switch between.
     */
    val showKingdomChips: Boolean get() = availableKingdoms.size > 1

    /** The use chips describe plants, so they follow the plants rather than the kingdoms. */
    val showUseChips: Boolean get() = plants.total > 0
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
    val kingdomOk = filters.kingdom == null || species.kingdom == filters.kingdom
    // M23: a plain membership test, which is why the medicinal tag is stored rather than
    // derived. An animal's `uses` is empty, so a use chip can never return one.
    val useOk = filters.use == null || filters.use in species.uses
    val classOk = filters.taxClass == null || species.taxClass == filters.taxClass
    val ecoOk = filters.ecosystemId == null || filters.ecosystemId in species.ecosystemIds
    return caughtOk && kingdomOk && useOk && classOk && ecoOk
}

/**
 * Which class chips the row renders (M23): the selected kingdom's classes, or all of them
 * when no kingdom is picked — and only classes the region actually holds.
 *
 * The [available] narrowing is beyond 11.4's rule and is the fix for the chip row shipped
 * after slice 9, which offered Trees / Shrubs / Herbs / Ferns against 120 animals: four
 * chips that could only ever empty the grid. Once slice 10's asset lands it changes nothing,
 * because every class then has species in it.
 *
 * The selected class is always kept, whatever either narrowing says. Tapping `Trees` and then
 * `Animals` is an ordinary thing to do, and dropping the Trees chip would leave an empty grid
 * narrowed by a filter with nothing on screen to un-tap.
 */
fun classChips(
    filters: DexGridFilters,
    available: Set<TaxClass> = TaxClass.entries.toSet(),
): List<TaxClass> = TaxClass.entries.filter {
    it == filters.taxClass ||
        (it in available && (filters.kingdom == null || it.kingdom == filters.kingdom))
}

/**
 * Search and filters compose: a species survives only if it satisfies every active narrowing.
 * Dex order is the grid's order (M01), and user-added species trail the catalogue because
 * their dex numbers start above [dev.tlong.biodex.domain.USER_DEX_NUMBER_BASE], with the
 * plants between the two (M02).
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
): Flow<DexGridUiState> =
    combine(species, ecosystems, progress, query, filters) { all, ecos, prog, q, f ->
        DexGridUiState(
            regionLabel = prog.regionName,
            animals = prog.animals,
            plants = prog.plants,
            fungi = prog.fungi,
            query = q,
            filters = f,
            ecosystems = ecos,
            species = filterSpecies(all, q, f),
            availableClasses = prog.perClass.map { it.first }.toSet(),
            loading = all.isEmpty() && prog.totalSpecies == 0,
        )
    }

/**
 * The use chips of M23. Adjectives, because they describe the plant rather than count it.
 *
 * A use chip narrows the grid to plants by construction: fungi carry no uses at all, by
 * design and not by omission (there is no Duke's data behind a mushroom, so any use claim
 * would be the curator's alone), and animals never had them.
 */
fun useChipLabel(use: PlantUse): String = when (use) {
    PlantUse.EDIBLE -> "Food source"
    PlantUse.MEDICINAL -> "Medicinal"
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
    TaxClass.TREE -> "Trees"
    TaxClass.SHRUB -> "Shrubs"
    TaxClass.HERB -> "Herbs"
    TaxClass.FERN -> "Ferns"
    TaxClass.MUSHROOM -> "Mushrooms"
    TaxClass.BRACKET -> "Brackets"
    TaxClass.OTHER_FUNGUS -> "Other fungi"
}
