package dev.tlong.biodex.ui.reveal

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * D62's capture beat: a ball closes around the silhouette, clicks, rocks itself still, and
 * opens on the photograph. Pure drawing over three clocks, so the overlay's timeline owns
 * every duration and this file owns only the geometry.
 *
 * The shape is the point and the colours are the app's: the muted `stop` red for the upper
 * half, the card white for the lower, the ink `fg` for the band and button rim. That reads as
 * the ball without borrowing anyone's trade dress, and it sits on both themes.
 *
 * @param close 0 → 1: the two halves travel in from above and below and meet at the equator.
 * @param wobble 0 → 1: a decaying rock about the ball's base, played after the click.
 * @param open 0 → 1: the halves fall away again and fade — the photograph is behind them.
 */
@Composable
fun PokeballShell(
    close: Float,
    wobble: Float,
    open: Float,
    colours: PokeballColours,
    modifier: Modifier = Modifier,
) {
    if (close <= 0f || open >= 1f) return
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        // The halves start one diameter out and arrive together; opening sends them back the
        // same way. An ease-out on the approach makes the click land rather than drift in.
        val approach = 1f - easeOut(close)
        val depart = easeIn(open)
        val travel = size.minDimension * (approach + depart)
        val alpha = (1f - open).coerceIn(0f, 1f)

        rotate(degrees = wobbleAngle(wobble), pivot = Offset(centre.x, centre.y + radius)) {
            // Upper hemisphere.
            translate(top = -travel) {
                drawHalf(centre, radius, upper = true, colour = colours.upper, alpha = alpha)
            }
            // Lower hemisphere.
            translate(top = travel) {
                drawHalf(centre, radius, upper = false, colour = colours.lower, alpha = alpha)
            }
            // The band and the button only exist once the halves have met: they are what says
            // "closed", so they fade up over the last stretch of the approach.
            val sealed = ((close - 0.85f) / 0.15f).coerceIn(0f, 1f) * alpha
            if (sealed > 0f) {
                val bandHeight = radius * BAND_FRACTION
                drawRect(
                    color = colours.band,
                    topLeft = Offset(centre.x - radius, centre.y - bandHeight / 2f),
                    size = Size(radius * 2f, bandHeight),
                    alpha = sealed,
                )
                val buttonRadius = radius * BUTTON_FRACTION
                drawCircle(colours.band, radius = buttonRadius + bandHeight * 0.45f, center = centre, alpha = sealed)
                drawCircle(colours.button, radius = buttonRadius, center = centre, alpha = sealed)
                drawCircle(
                    colours.band,
                    radius = buttonRadius * 0.55f,
                    center = centre,
                    alpha = sealed,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

data class PokeballColours(
    val upper: Color,
    val lower: Color,
    val band: Color,
    val button: Color,
)

/** Half a disc, clipped to the ball's own circle so the travelling half never shows a chord edge. */
private fun DrawScope.drawHalf(centre: Offset, radius: Float, upper: Boolean, colour: Color, alpha: Float) {
    val clip = Path().apply {
        val bounds = Rect(centre.x - radius, centre.y - radius, centre.x + radius, centre.y + radius)
        // Arc from the equator, through the top (upper) or the bottom (lower).
        arcTo(bounds, startAngleDegrees = if (upper) 180f else 0f, sweepAngleDegrees = 180f, forceMoveTo = true)
        close()
    }
    clipPath(clip) {
        drawCircle(colour, radius = radius, center = centre, alpha = alpha)
    }
}

/**
 * Three rocks that die away: `sin` for the motion, `(1 - t)` for the decay, and a squared
 * decay so the last rock is a settle rather than a swing.
 */
internal fun wobbleAngle(progress: Float): Float {
    if (progress <= 0f || progress >= 1f) return 0f
    val decay = (1f - progress) * (1f - progress)
    return (WOBBLE_DEGREES * sin(progress * WOBBLE_ROCKS * PI) * decay).toFloat()
}

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)
private fun easeIn(t: Float): Float = t * t

private const val BAND_FRACTION = 0.13f
private const val BUTTON_FRACTION = 0.22f
/** Ten, not more: the ball sits 14dp inside the halo's clip, and a steeper lean would cross it. */
private const val WOBBLE_DEGREES = 10f
private const val WOBBLE_ROCKS = 3
