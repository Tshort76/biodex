package dev.tlong.biodex.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.data.photo.ownedFileModel
import dev.tlong.biodex.ui.common.ClassMeter
import dev.tlong.biodex.ui.common.EcosystemMeter
import dev.tlong.biodex.ui.common.EcosystemMeterPair
import dev.tlong.biodex.ui.common.MeterGroupHeader
import dev.tlong.biodex.ui.common.MeterBar
import dev.tlong.biodex.ui.common.ProgressPill
import dev.tlong.biodex.ui.common.RegionPill
import dev.tlong.biodex.ui.common.SectionHeader
import dev.tlong.biodex.ui.common.Silhouettes
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.grid.DexBottomBar
import dev.tlong.biodex.ui.theme.DexTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Frame 5 of `mockup.html` (M15/S08): the big fraction with its meter, the seven ecosystem
 * meters, the class bars, and the recently-caught strip.
 *
 * Everything on this screen is read-only and derived — nothing here can change the
 * collection, which is why it is the screen the user can trust to reconcile with the grid.
 */
@Composable
fun StatsRoute(
    onBack: () -> Unit,
    onOpenSpecies: (String) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatsScreen(state = state, onBack = onBack, onOpenSpecies = onOpenSpecies)
}

@Composable
fun StatsScreen(
    state: StatsUiState,
    onBack: () -> Unit,
    onOpenSpecies: (String) -> Unit,
) {
    val colors = DexTheme.colors
    Scaffold(
        containerColor = colors.bg,
        bottomBar = { DexBottomBar(selected = 1, onDex = onBack, onStats = {}) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = "Stats",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.fg,
                )
                Box(modifier = Modifier.weight(1f))
                if (state.regionLabel.isNotEmpty()) RegionPill(state.regionLabel)
            }

            // M29's pills, one per kingdom, under the title rather than beside it: the Stats
            // header has no settings button to compete with, so the numbers get their own
            // line — which is also what leaves room for a third.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                ProgressPill(caught = state.overall.caught, total = state.overall.total)
                if (state.showPlantPill) {
                    ProgressPill(
                        caught = state.plants.caught,
                        total = state.plants.total,
                        color = colors.ok,
                        glyph = "\uD83C\uDF3F",
                    )
                }
                if (state.showFungi) {
                    ProgressPill(
                        caught = state.fungi.caught,
                        total = state.fungi.total,
                        color = colors.warn,
                        glyph = "\uD83C\uDF44",
                    )
                }
            }

            OverallMeter(state)

            SectionHeader(ecosystemHeader(state))
            if (state.ecosystems.isEmpty()) {
                EmptyNote("No ecosystems yet.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.ecosystems.forEach { progress ->
                        when {
                            state.showFungi -> EcosystemMeterTriple(
                                label = progress.ecosystem.name,
                                animals = progress.animals,
                                plants = progress.plants,
                                fungi = progress.fungi,
                            )

                            state.showPlants -> EcosystemMeterPair(
                                label = progress.ecosystem.name,
                                animals = progress.animals,
                                plants = progress.plants,
                            )

                            else -> EcosystemMeter(
                                label = progress.ecosystem.name,
                                meter = progress.animals,
                            )
                        }
                    }
                }
            }

            SectionHeader("By class")
            if (state.classes.isEmpty()) {
                EmptyNote("No species yet.")
            } else if (!state.multipleKingdoms) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.classes.forEach { row ->
                        ClassMeter(label = row.label, meter = row.meter)
                    }
                }
            } else {
                // 11.4's groups, now three. A group whose list is empty draws no sub-header,
                // so a region with only one kingdom in it never shows a heading over nothing
                // — which is also what keeps this correct on a catalogue with no fungi.
                ClassGroup("Animals", state.animalClasses, colors.accent)
                ClassGroup("Plants", state.plantClasses, colors.ok)
                ClassGroup("Fungi", state.fungusClasses, colors.warn)
            }

            SectionHeader("Recently caught")
            if (state.recent.isEmpty()) {
                EmptyNote("Nothing caught yet — register your first species from the dex.")
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    state.recent.forEach { catch ->
                        RecentCatchTile(catch, onClick = { onOpenSpecies(catch.speciesId) })
                    }
                }
            }

            Box(modifier = Modifier.height(16.dp))
            TextButton(onClick = onBack) {
                Text(text = "← Back to the dex", color = colors.accent)
            }
        }
    }
}

/**
 * `.big` — the headline. One kingdom gets the shipped card (`47 / 120` and a percent line);
 * two or three kingdoms get `.big.two`: a block each, side by side, never one blended
 * fraction (D13).
 *
 * Three blocks stay side by side rather than going two-plus-one. The row is the whole point
 * of the layout — three life lists the eye compares in one movement — and stacking the third
 * underneath would read as a subordinate kingdom rather than a third one. What it costs is
 * width: on a 360dp phone each block gets about 93dp, so the three-up path drops the numeral
 * a size (see [KingdomBlock]) to keep `120 / 120` on one line.
 */
@Composable
private fun OverallMeter(state: StatsUiState) {
    val colors = DexTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.rule, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Only the kingdoms the region actually has get a block, so a catalogue with no
        // fungi renders exactly the two-up card it shipped with and one with no plants
        // never draws "Plants 0 / 0" beside the mushrooms.
        val blocks = buildList {
            add(Triple("Animals", state.overall, colors.accent))
            if (state.showPlants) add(Triple("Plants", state.plants, colors.ok))
            if (state.showFungi) add(Triple("Fungi", state.fungi, colors.warn))
        }
        if (blocks.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(if (blocks.size > 2) 10.dp else 14.dp)) {
                blocks.forEach { (label, meter, fill) ->
                    KingdomBlock(
                        label = label,
                        meter = meter,
                        fill = fill,
                        compact = blocks.size > 2,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${state.overall.caught}",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.fg,
                )
                Text(
                    text = " / ${state.overall.total}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.muted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        summaryLine(state).takeIf { it.isNotEmpty() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        if (!state.multipleKingdoms) {
            MeterBar(
                fraction = state.overall.fraction,
                fill = colors.accent,
                height = 10,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One block of `.big.two`: the kingdom's name, its fraction, and its own bar.
 *
 * [compact] drops the numeral from `headlineMedium` to `headlineSmall`. Three blocks on a
 * 360dp phone leave about 93dp each, which the larger numeral plus ` / 120` overruns — and
 * it overruns by wrapping, which no unit test and no `assembleDebug` would have caught.
 */
@Composable
private fun KingdomBlock(
    label: String,
    meter: dev.tlong.biodex.domain.Meter,
    fill: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = DexTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            ),
            color = colors.muted,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            val numeral =
                if (compact) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.headlineMedium
            Text(
                text = "${meter.caught}",
                style = numeral.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.fg,
                maxLines = 1,
            )
            Text(
                text = " / ${meter.total}",
                style = (
                    if (compact) MaterialTheme.typography.bodySmall
                    else MaterialTheme.typography.bodyMedium
                    ).copy(fontFeatureSettings = "tnum"),
                color = colors.muted,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        MeterBar(fraction = meter.fraction, fill = fill, height = 8, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * `.brow.eco.three` — one ecosystem, all three kingdoms. Three thin stacked bars and one
 * value cell reading `12/24 · 2/15 · 1/9`, on D13's rule: an ecosystem row answers "what is
 * left to find here", and 12 of 24 animals, 2 of 15 plants and 1 of 9 fungi are three
 * different errands that must never blend into one bar.
 *
 * It lives here rather than beside `EcosystemMeterPair` in `ui/common` because it is the
 * Stats screen's alone, and it takes the *kingdom identity* colours — accent, ok, warn —
 * rather than the pair's `warn` for animals. With three kingdoms sharing a row, `warn`
 * cannot mean animals here and fungi in the class bars two sections down; the colour has to
 * name the kingdom or it names nothing.
 */
@Composable
private fun EcosystemMeterTriple(
    label: String,
    animals: dev.tlong.biodex.domain.Meter,
    plants: dev.tlong.biodex.domain.Meter,
    fungi: dev.tlong.biodex.domain.Meter,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(88.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            MeterBar(animals.fraction, colors.accent, Modifier.fillMaxWidth(), height = 4)
            MeterBar(plants.fraction, colors.ok, Modifier.fillMaxWidth(), height = 4)
            MeterBar(fungi.fraction, colors.warn, Modifier.fillMaxWidth(), height = 4)
        }
        Text(
            text = "${animals.caught}/${animals.total} · ${plants.caught}/${plants.total} · " +
                "${fungi.caught}/${fungi.total}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = colors.fg,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1,
            // Wide enough for `12/24 · 2/15 · 1/9`; the pair's 74dp is not.
            modifier = Modifier.width(104.dp),
        )
    }
}

/** One group of class bars under its sub-header; nothing at all when the group is empty. */
@Composable
private fun ClassGroup(
    label: String,
    rows: List<ClassRow>,
    fill: androidx.compose.ui.graphics.Color,
) {
    if (rows.isEmpty()) return
    MeterGroupHeader(label)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row -> ClassMeter(label = row.label, meter = row.meter, fill = fill) }
    }
}

/**
 * The section header over the ecosystem meters, naming the kingdoms whose bars are stacked
 * in each row so the `12/24 · 2/15 · 1/9` cell can be read without counting bars.
 */
internal fun ecosystemHeader(state: StatsUiState): String = when {
    state.showFungi -> "By ecosystem · animals / plants / fungi"
    state.showPlants -> "By ecosystem · animals / plants"
    else -> "By ecosystem"
}

/**
 * The mockup's `39% caught · +3 of your own · last new catch Aug 30, 2026`. Each clause
 * disappears when it has nothing to say, rather than reading "+0".
 *
 * The percentage is dropped once the region has a second kingdom: frame 5 drops it too, and
 * one percentage over two or three life lists would have to blend them, which D13 forbids.
 * Each kingdom's own bar carries its share instead.
 */
internal fun summaryLine(state: StatsUiState): String = buildList {
    if (!state.multipleKingdoms) add("${state.percentCaught}% caught")
    if (state.userAdded > 0) add("+${state.userAdded} of your own")
    state.lastCatchAt?.let { add("last new catch ${statsDateFormat.format(Date(it))}") }
}.joinToString(" · ")

/** `.recent .ph` — one tile: the species' own photo if it has one, else its silhouette. */
@Composable
private fun RecentCatchTile(catch: RecentCatch, onClick: () -> Unit) {
    val colors = DexTheme.colors
    val context = LocalContext.current
    val thumbModel = remember(catch.thumbPath) { ownedFileModel(context.filesDir, catch.thumbPath) }
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(colors.silBg),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    error = painterResource(
                        Silhouettes.resolve(context, catch.silhouetteRes, catch.taxClass),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SilhouetteIcon(
                    silhouetteRes = catch.silhouetteRes,
                    taxClass = catch.taxClass,
                    size = 44.dp,
                    tint = colors.accent,
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                text = catch.commonName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = recentDateFormat.format(Date(catch.caughtAt)),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.faint,
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DexTheme.colors.faint,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

private val statsDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
private val recentDateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
