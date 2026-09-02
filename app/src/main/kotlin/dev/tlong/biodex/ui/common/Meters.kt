package dev.tlong.biodex.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tlong.biodex.domain.Meter
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * The meters of ARCHITECTURE.md 6.4, deferred by slice 4 to their only caller — the Stats
 * screen (`mockup.html` frame 5, `.brow`).
 *
 * The colour split is the mockup's and is intentional: **ecosystem meters fill with `warn`,
 * class meters with `accent`**, so a glance tells the two breakdowns apart even though the
 * rows are otherwise identical.
 *
 * D9's addendum is the other rule these components exist to hold. A user-added species is
 * shown as a trailing `+1` beside the fraction, never inside it — the bar's fill and the
 * `12/24` are curated species only.
 */

/** `.brow.eco` — one ecosystem's progress. */
@Composable
fun EcosystemMeter(
    label: String,
    meter: Meter,
    modifier: Modifier = Modifier,
) = MeterRow(label, meter, DexTheme.colors.warn, modifier)

/**
 * `.brow.eco.two` — one ecosystem, both kingdoms. Two thin stacked bars (animal `warn`,
 * plant `ok`) and one value cell reading `12/24 · 2/15`.
 *
 * The two kingdoms are never blended into one bar, here least of all: an ecosystem row is
 * the answer to "what is left to find here", and 12 of 24 animals is a different errand
 * from 2 of 15 plants (D13).
 */
@Composable
fun EcosystemMeterPair(
    label: String,
    animals: Meter,
    plants: Meter,
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
            modifier = Modifier.width(96.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            MeterBar(
                fraction = animals.fraction,
                fill = colors.warn,
                height = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            MeterBar(
                fraction = plants.fraction,
                fill = colors.ok,
                height = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = "${animals.caught}/${animals.total} · ${plants.caught}/${plants.total}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = colors.fg,
            textAlign = TextAlign.End,
            maxLines = 1,
            // Wide enough for `12/24 · 2/15`; the single-kingdom row's 44dp is not.
            modifier = Modifier.width(74.dp),
        )
    }
}

/**
 * `.brow` — one taxonomic class's progress. [fill] is `accent` for an animal class and `ok`
 * for a plant one (`.brow.plant`), which is the same colour language the two progress pills
 * and the two overall meters use.
 */
@Composable
fun ClassMeter(
    label: String,
    meter: Meter,
    modifier: Modifier = Modifier,
    fill: Color = DexTheme.colors.accent,
) = MeterRow(label, meter, fill, modifier)

/** `.subhd` — the small muted heading over a group of class bars ("Animals", "Plants"). */
@Composable
fun MeterGroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = DexTheme.colors.muted,
        modifier = modifier.padding(top = 4.dp, bottom = 1.dp),
    )
}

@Composable
private fun MeterRow(
    label: String,
    meter: Meter,
    fill: Color,
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
            modifier = Modifier.width(112.dp),
        )
        MeterBar(fraction = meter.fraction, fill = fill, modifier = Modifier.weight(1f))
        Text(
            text = "${meter.caught}/${meter.total}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = colors.fg,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )
        // D9: outside the fraction, always. A blank slot keeps the rows aligned when a
        // meter has no user-added species of its own.
        Text(
            text = if (meter.userAdded > 0) "+${meter.userAdded}" else "",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontFeatureSettings = "tnum",
            ),
            color = colors.faint,
            maxLines = 1,
            modifier = Modifier.width(24.dp),
        )
    }
}

/** `.bar` — the track and its fill. Shared by the meters and the big overall meter. */
@Composable
fun MeterBar(
    fraction: Float,
    fill: Color,
    modifier: Modifier = Modifier,
    height: Int = 8,
) {
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(height.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(DexTheme.colors.silBg),
    ) {
        if (safe > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    // fillMaxWidth(0f) would still round up to a visible sliver on some
                    // densities, so a zero fraction draws nothing at all instead.
                    .layout { measurable, constraints ->
                        val width = (constraints.maxWidth * safe).toInt().coerceAtLeast(1)
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = width, maxWidth = width),
                        )
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .clip(RoundedCornerShape(percent = 50))
                    .background(fill),
            )
        }
    }
}
