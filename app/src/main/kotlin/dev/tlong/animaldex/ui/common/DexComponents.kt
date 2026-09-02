package dev.tlong.animaldex.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.tlong.animaldex.data.photo.ownedFileModel
import dev.tlong.animaldex.domain.SpeciesSummary
import dev.tlong.animaldex.media.CallRowState
import dev.tlong.animaldex.ui.theme.DexTheme

// ---------------------------------------------------------------------------
// The component vocabulary of ARCHITECTURE.md 6.4, each element matching one
// piece of mockup.html's CSS. Nothing here reaches a repository or a ViewModel:
// every component takes plain values, which is what makes the previews cheap.
// ---------------------------------------------------------------------------

/** `.appbar .region` — the region name as an uppercase warn-on-warnSoft pill. */
@Composable
fun RegionPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
        color = DexTheme.colors.warn,
        modifier = modifier
            .clip(CircleShape)
            .background(DexTheme.colors.warnSoft)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** `.appbar .prog` — `47 / 120` in accent on accentSoft, tabular so it does not jitter. */
@Composable
fun ProgressPill(caught: Int, total: Int, modifier: Modifier = Modifier) {
    Text(
        text = "$caught / $total",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = DexTheme.colors.accent,
        modifier = modifier
            .clip(CircleShape)
            .background(DexTheme.colors.accentSoft)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** `.chip` / `.chip.on` — outlined when off, accent on accentSoft when selected. */
@Composable
fun DexFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = if (selected) colors.accent else colors.muted,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .border(1.dp, if (selected) colors.accent else colors.rule, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** `.sechd` — the small uppercase faint section header used all over the detail screen. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        ),
        color = DexTheme.colors.faint,
        modifier = modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** `.attr` — the fine print under the detail screen. */
@Composable
fun AttributionLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
        color = DexTheme.colors.faint,
        modifier = modifier,
    )
}

/**
 * `.cell` — one grid cell (M01). A caught species shows the user's own photo; an uncaught one
 * shows the class silhouette on `silBg`.
 *
 * The photo comes from the capture's **stored thumbnail**, never from the gallery URI (M11).
 * That is the rule that makes a broken reference a one-photo problem rather than a blank
 * collection: the grid does not resolve anything, so it cannot fail. If the thumbnail file is
 * somehow missing, Coil's error slot falls back to the silhouette rather than a hole.
 */
@Composable
fun SpeciesCell(
    species: SpeciesSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    val filesDir = LocalContext.current.filesDir
    val thumbModel = remember(species.thumbPath) { ownedFileModel(filesDir, species.thumbPath) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .background(colors.silBg),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    error = painterResource(
                        Silhouettes.resolve(
                            LocalContext.current,
                            species.silhouetteRes,
                            species.taxClass,
                        ),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SilhouetteIcon(
                    silhouetteRes = species.silhouetteRes,
                    taxClass = species.taxClass,
                    size = 56.dp,
                    tint = if (species.caught) colors.accent else colors.sil,
                )
            }
            if (species.caught) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ok,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(colors.accentSoft)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                text = species.displayNumber,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.faint,
            )
            Text(
                text = species.commonName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (species.caught) colors.fg else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `.player` — the call row (M04/M06). Every state it can show is decided by
 * [dev.tlong.animaldex.media.callRowState]; this composable only paints one.
 *
 * The row renders in **every** state, including the disabled "No call available" one — which
 * is every species in the shipped catalogue, since no Xeno-canto key exists yet (5.4). It is
 * deliberately not hidden: the row the user sees today is the row that comes alive when calls
 * arrive, with no layout change.
 */
@Composable
fun CallPlayerRow(
    state: CallRowState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    val buttonColor = when {
        !state.enabled -> colors.rule
        state.failed -> colors.stop
        else -> colors.accent
    }
    val waveColor = when {
        !state.enabled -> colors.faint
        state.failed -> colors.stop
        state.playing -> colors.accent
        else -> colors.accent.copy(alpha = 0.7f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (state.failed) colors.stopSoft else colors.codeBg)
            .then(if (state.enabled) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(buttonColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    state.loading -> "…"
                    state.playing -> "■"
                    state.failed -> "↻"
                    else -> "▶"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.enabled) colors.card else colors.faint,
            )
        }
        StaticWaveform(color = waveColor, modifier = Modifier.weight(1f))
        Text(
            text = state.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = if (state.failed) colors.stop else colors.faint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.1f),
        )
    }
}

/** `.wave` — decoration, not data: a fixed bar pattern copied from the mockup. */
@Composable
private fun StaticWaveform(color: Color, modifier: Modifier = Modifier) {
    val heights = listOf(30, 65, 95, 70, 40, 85, 55, 90, 35, 60, 25, 75, 45, 20)
    Row(
        modifier = modifier.height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        heights.forEach { pct ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((22 * pct / 100).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color.copy(alpha = 0.55f)),
            )
        }
    }
}

/** `.linkrow` — the outbound "Learn more" row; disabled-looking when there is no URL. */
@Composable
fun LinkRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) colors.accent else colors.faint,
        )
        Text(
            text = "↗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) colors.accent else colors.faint,
        )
    }
}

/** `.caughtchip` — `✓ Caught · Aug 30, 2026`. */
@Composable
fun CaughtChip(dateLabel: String, modifier: Modifier = Modifier) {
    Text(
        text = "✓ Caught · $dateLabel",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = DexTheme.colors.ok,
        modifier = modifier
            .clip(CircleShape)
            .background(DexTheme.colors.accentSoft)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** `.sci` — the italic scientific name under a species title. */
@Composable
fun ScientificName(name: String, modifier: Modifier = Modifier) {
    Text(
        text = name,
        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
        color = DexTheme.colors.muted,
        modifier = modifier,
    )
}
