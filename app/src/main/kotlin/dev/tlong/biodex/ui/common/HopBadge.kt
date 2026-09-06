package dev.tlong.biodex.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * D36's hop badge: the number of steps to another species, inside a shape that says which
 * rank you had to climb to.
 *
 * | hops | shape | meaning |
 * |---|---|---|
 * | 2 | filled circle | same family |
 * | 4 | rounded square | same order |
 * | 6 | square | same class |
 * | 8 | diamond | same phylum |
 * | 10 | hexagon | same kingdom |
 * | 12 | dotted ring | nothing shared but life |
 *
 * The point of the shape is that a list can be read without reading digits — three hexagons
 * says "nothing close here" before you have parsed a single number. Shape is never the only
 * carrier: every row that shows one also spells the rank out in words beside it, and the two
 * nearest tiers take the accent while the rest recede, so the ordering survives for anyone
 * the shapes do not land for.
 *
 * The 12 badge exists but no screen can reach it: 12 only happens between kingdoms, and
 * every species has same-kingdom neighbours far closer than that. It is drawn here so the
 * scale is complete rather than because it will be seen.
 */
@Composable
fun HopBadge(hops: Int, size: Dp = 30.dp, modifier: Modifier = Modifier) {
    val colors = DexTheme.colors
    val near = hops <= 4
    val stroke = when {
        near -> colors.accent
        hops <= 8 -> colors.muted
        else -> colors.faint
    }
    val fill = if (near) colors.accentSoft else Color.Transparent
    val ink = when {
        near -> colors.accent
        hops <= 8 -> colors.fg
        else -> colors.muted
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            drawHopShape(hops = hops, fill = fill, stroke = stroke)
        }
        Text(
            text = hops.toString(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            ),
            color = ink,
        )
    }
}

private fun DrawScope.drawHopShape(hops: Int, fill: Color, stroke: Color) {
    val w = size.minDimension
    val inset = w * 0.11f
    val side = w - inset * 2
    val line = Stroke(width = w * 0.055f)
    val cx = w / 2f
    val cy = size.height / 2f
    val r = side / 2f

    fun polygon(points: List<Pair<Float, Float>>) = Path().apply {
        moveTo(points[0].first, points[0].second)
        points.drop(1).forEach { lineTo(it.first, it.second) }
        close()
    }

    when (hops) {
        2 -> {
            if (fill != Color.Transparent) {
                drawCircle(color = fill, radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy))
            }
            drawCircle(
                color = stroke,
                radius = r,
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                style = line,
            )
        }
        4, 6 -> {
            val corner = if (hops == 4) w * 0.28f else w * 0.09f
            val radius = androidx.compose.ui.geometry.CornerRadius(corner, corner)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, cy - r)
            val boxSize = androidx.compose.ui.geometry.Size(side, side)
            if (fill != Color.Transparent) {
                drawRoundRect(color = fill, topLeft = topLeft, size = boxSize, cornerRadius = radius)
            }
            drawRoundRect(
                color = stroke,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = radius,
                style = line,
            )
        }
        8 -> drawPath(
            path = polygon(
                listOf(cx to cy - r, cx + r to cy, cx to cy + r, cx - r to cy),
            ),
            color = stroke,
            style = line,
        )
        10 -> {
            // Flat-topped is wider than tall and reads as squashed at 30dp; point-topped
            // fills the same box as the diamond, so the two sit level in a column.
            val h = r * 0.866f
            drawPath(
                path = polygon(
                    listOf(
                        cx to cy - r, cx + h to cy - r / 2f, cx + h to cy + r / 2f,
                        cx to cy + r, cx - h to cy + r / 2f, cx - h to cy - r / 2f,
                    ),
                ),
                color = stroke,
                style = line,
            )
        }
        else -> drawCircle(
            color = stroke,
            radius = r,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
            style = Stroke(
                width = w * 0.055f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.05f, w * 0.1f)),
            ),
        )
    }
}
