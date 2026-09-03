package dev.tlong.biodex.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Meter
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.ui.common.DexFilterChip
import dev.tlong.biodex.ui.common.ProgressPill
import dev.tlong.biodex.ui.common.RegionPill
import dev.tlong.biodex.ui.common.SpeciesCell
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * Frame 1 of `mockup.html`: the dex grid (M01/M02/M14). App bar with region and progress
 * pills, live search, a composing chip row, a three-column silhouette grid, and the bottom
 * bar — Dex and Stats only, because Map is C01 and out of v1 (ARCHITECTURE.md 6.1).
 */
@Composable
fun DexGridRoute(
    onOpenSpecies: (String) -> Unit,
    onRegister: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalContext.current.appContainer
    val viewModel: DexGridViewModel = viewModel(factory = DexGridViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DexGridScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onCaughtFilter = viewModel::onCaughtFilter,
        onKingdomFilter = viewModel::onKingdomFilter,
        onUseFilter = viewModel::onUseFilter,
        onClassFilter = viewModel::onClassFilter,
        onEcosystemFilter = viewModel::onEcosystemFilter,
        onClearFilters = viewModel::onClearFilters,
        onOpenSpecies = onOpenSpecies,
        onRegister = onRegister,
        onOpenStats = onOpenStats,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun DexGridScreen(
    state: DexGridUiState,
    onQueryChange: (String) -> Unit,
    onCaughtFilter: (CaughtFilter) -> Unit,
    onKingdomFilter: (Kingdom) -> Unit,
    onUseFilter: (PlantUse) -> Unit,
    onClassFilter: (TaxClass) -> Unit,
    onEcosystemFilter: (String) -> Unit,
    onClearFilters: () -> Unit,
    onOpenSpecies: (String) -> Unit,
    onRegister: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = DexTheme.colors
    Scaffold(
        containerColor = colors.bg,
        bottomBar = { DexBottomBar(selected = 0, onDex = {}, onStats = onOpenStats) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onRegister,
                containerColor = colors.accent,
                contentColor = colors.card,
            ) {
                Text(text = "＋", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 12.dp),
        ) {
            GridAppBar(
                regionLabel = state.regionLabel,
                animals = state.animals,
                plants = state.plants.takeIf { state.showPlantPill },
                fungi = state.fungi.takeIf { state.showFungiPill },
                onOpenSettings = onOpenSettings,
            )
            SearchField(query = state.query, onQueryChange = onQueryChange)
            FilterChipRow(
                state = state,
                onCaughtFilter = onCaughtFilter,
                onKingdomFilter = onKingdomFilter,
                onUseFilter = onUseFilter,
                onClassFilter = onClassFilter,
                onEcosystemFilter = onEcosystemFilter,
                onClearFilters = onClearFilters,
            )
            when {
                state.loading -> CentredNote("Loading the Pacific catalogue…")
                state.species.isEmpty() -> CentredNote(
                    if (state.isFiltered) "No species match." else "The catalogue is empty.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.species, key = { it.id }) { species ->
                        SpeciesCell(species = species, onClick = { onOpenSpecies(species.id) })
                    }
                }
            }
        }
    }
}

/**
 * M29's header: the name, the region, and one progress pill per kingdom.
 *
 * "PACIFIC USA" and two pills are a lot to fit next to a title on a phone, so the title is
 * the thing that gives way — `weight(1f, fill = false)` lets it shrink and ellipsise while
 * the numbers, which are the point of the header, stay whole.
 */
@Composable
private fun GridAppBar(
    regionLabel: String,
    animals: Meter,
    plants: Meter?,
    fungi: Meter?,
    onOpenSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = "BioDex",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = DexTheme.colors.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Deliberately unweighted. A weighted title splits the leftover width with the
            // spacer below it, which was invisible at two progress pills and truncated the
            // product name to "Bio…" at three. The name is a fixed short string; it should
            // measure at its own size and let the spacer absorb whatever is left.
        )
        if (regionLabel.isNotEmpty()) RegionPill(regionLabel)
        Box(modifier = Modifier.weight(1f))
        ProgressPill(caught = animals.caught, total = animals.total)
        plants?.let {
            ProgressPill(
                caught = it.caught,
                total = it.total,
                color = DexTheme.colors.ok,
                glyph = "\uD83C\uDF3F",
            )
        }
        fungi?.let {
            ProgressPill(
                caught = it.caught,
                total = it.total,
                color = DexTheme.colors.warn,
                glyph = "\uD83C\uDF44",
            )
        }
        TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(text = "⚙", color = DexTheme.colors.muted)
        }
    }
}

/**
 * `.searchbar` — a bare field on `codeBg` rather than a Material `TextField`, whose container
 * and indicator would fight the mockup's flat pill.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = "🔍", style = MaterialTheme.typography.bodySmall, color = colors.faint)
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search species…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.faint,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.fg,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.bodySmall,
                color = colors.faint,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onQueryChange("") }
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * `.chips` — one horizontally scrolling row holding all five filter dimensions in the
 * mockup's order: All, caught state, kingdoms, uses, classes, then ecosystems. They compose
 * (M14/M23): picking `Plants` and `Edible` narrows to plants with an edible use, and every
 * dimension ANDs with the search query.
 *
 * The class chips are the selected kingdom's (M23) — which is also the fix for the row
 * offering Trees / Shrubs / Herbs / Ferns against a catalogue that had no plants in it.
 */
@Composable
private fun FilterChipRow(
    state: DexGridUiState,
    onCaughtFilter: (CaughtFilter) -> Unit,
    onKingdomFilter: (Kingdom) -> Unit,
    onUseFilter: (PlantUse) -> Unit,
    onClassFilter: (TaxClass) -> Unit,
    onEcosystemFilter: (String) -> Unit,
    onClearFilters: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        DexFilterChip(
            label = "All",
            selected = state.filters.isEmpty,
            onClick = onClearFilters,
        )
        DexFilterChip(
            label = "Caught",
            selected = state.filters.caught == CaughtFilter.CAUGHT,
            onClick = { onCaughtFilter(CaughtFilter.CAUGHT) },
        )
        DexFilterChip(
            label = "Uncaught",
            selected = state.filters.caught == CaughtFilter.UNCAUGHT,
            onClick = { onCaughtFilter(CaughtFilter.UNCAUGHT) },
        )
        if (state.showKingdomChips) {
            // Enum order, filtered by what the region holds — never `Kingdom.entries` raw,
            // or an empty kingdom gets a chip whose only result is an empty grid.
            Kingdom.entries.filter { it in state.availableKingdoms }.forEach { kingdom ->
                DexFilterChip(
                    label = kingdomChipLabel(kingdom),
                    selected = state.filters.kingdom == kingdom,
                    onClick = { onKingdomFilter(kingdom) },
                )
            }
        }
        if (state.showUseChips) {
            PlantUse.entries.forEach { use ->
                DexFilterChip(
                    label = useChipLabel(use),
                    selected = state.filters.use == use,
                    onClick = { onUseFilter(use) },
                )
            }
        }
        classChips(state.filters, state.availableClasses).forEach { taxClass ->
            DexFilterChip(
                label = taxClass.chipLabel(),
                selected = state.filters.taxClass == taxClass,
                onClick = { onClassFilter(taxClass) },
            )
        }
        state.ecosystems.forEach { ecosystem ->
            DexFilterChip(
                label = ecosystem.name,
                selected = state.filters.ecosystemId == ecosystem.id,
                onClick = { onEcosystemFilter(ecosystem.id) },
            )
        }
    }
}

@Composable
private fun CentredNote(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = DexTheme.colors.faint,
        )
    }
}

/** `.nav` — Dex and Stats; the mockup's Map tab is C01 and not in v1 (6.1). */
@Composable
fun DexBottomBar(selected: Int, onDex: () -> Unit, onStats: () -> Unit) {
    val colors = DexTheme.colors
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.accent,
        selectedTextColor = colors.accent,
        unselectedIconColor = colors.faint,
        unselectedTextColor = colors.faint,
        indicatorColor = colors.accentSoft,
    )
    NavigationBar(containerColor = colors.bg, contentColor = colors.faint) {
        NavigationBarItem(
            selected = selected == 0,
            onClick = onDex,
            colors = itemColors,
            icon = { Text("▦") },
            label = { Text("Dex", style = MaterialTheme.typography.labelSmall) },
        )
        NavigationBarItem(
            selected = selected == 1,
            onClick = onStats,
            colors = itemColors,
            icon = { Text("▲") },
            label = { Text("Stats", style = MaterialTheme.typography.labelSmall) },
        )
    }
}

// ---------------------------------------------------------------------------
// Previews. Nothing renders them on this build machine (R6), but they cost
// little and make the first Android Studio session useful. They are built from
// plain UiState values — never from a ViewModel or the AppContainer.
// ---------------------------------------------------------------------------

internal fun previewSpecies(
    id: String,
    number: Int,
    name: String,
    taxClass: TaxClass,
    caught: Boolean = false,
    ecosystems: List<String> = listOf("oak-chaparral"),
) = SpeciesSummary(
    id = id,
    regionId = "pacific",
    dexNumber = number,
    source = SpeciesSource.CURATED,
    detailsPending = false,
    commonName = name,
    scientificName = "Genus species",
    taxClass = taxClass,
    kingdom = taxClass.kingdom,
    silhouetteRes = "sil_" + taxClass.wireName,
    ecosystemIds = ecosystems,
    caughtAt = if (caught) 1_756_512_000_000L else null,
    thumbPath = null,
    captureCount = if (caught) 1 else 0,
)

@Preview(name = "Dex grid", widthDp = 380, heightDp = 780)
@Composable
private fun DexGridPreview() {
    BioDexTheme {
        DexGridScreen(
            state = DexGridUiState(
                regionLabel = "Pacific USA",
                animals = Meter(3, 120),
                ecosystems = listOf(
                    Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
                    Ecosystem("oak-chaparral", "pacific", "Oak Woodland & Chaparral", 3),
                ),
                species = listOf(
                    previewSpecies("heron", 3, "Great Blue Heron", TaxClass.BIRD, caught = true),
                    previewSpecies("hum", 8, "Anna's Hummingbird", TaxClass.BIRD, caught = true),
                    previewSpecies("jay", 14, "Steller's Jay", TaxClass.BIRD, caught = true),
                    previewSpecies("deer", 41, "Black-tailed Deer", TaxClass.MAMMAL),
                    previewSpecies("coyote", 49, "Coyote", TaxClass.MAMMAL),
                    previewSpecies("lizard", 66, "Western Fence Lizard", TaxClass.REPTILE),
                    previewSpecies("frog", 77, "Pacific Tree Frog", TaxClass.AMPHIBIAN),
                    previewSpecies("slug", 88, "Banana Slug", TaxClass.OTHER_INVERTEBRATE),
                    previewSpecies("monarch", 101, "Monarch Butterfly", TaxClass.INSECT),
                ),
                loading = false,
            ),
            onQueryChange = {},
            onCaughtFilter = {},
            onKingdomFilter = {},
            onUseFilter = {},
            onClassFilter = {},
            onEcosystemFilter = {},
            onClearFilters = {},
            onOpenSpecies = {},
            onRegister = {},
            onOpenStats = {},
            onOpenSettings = {},
        )
    }
}

/** Kept so the search field's "typed" state is visible in a preview too. */
@Preview(name = "Dex grid — searching", widthDp = 380, heightDp = 780)
@Composable
private fun DexGridSearchPreview() {
    BioDexTheme {
        DexGridScreen(
            state = DexGridUiState(
                regionLabel = "Pacific USA",
                animals = Meter(3, 120),
                query = "western",
                filters = DexGridFilters(taxClass = TaxClass.REPTILE),
                species = listOf(
                    previewSpecies("lizard", 66, "Western Fence Lizard", TaxClass.REPTILE),
                ),
                loading = false,
            ),
            onQueryChange = {},
            onCaughtFilter = {},
            onKingdomFilter = {},
            onUseFilter = {},
            onClassFilter = {},
            onEcosystemFilter = {},
            onClearFilters = {},
            onOpenSpecies = {},
            onRegister = {},
            onOpenStats = {},
            onOpenSettings = {},
        )
    }
}
