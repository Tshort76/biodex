package dev.tlong.biodex.ui.reveal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * D40's confetti: the colour the reveal had been refusing itself. A burst of small paper
 * pieces thrown up from the halo, riding a simple ballistic arc — out and up, then falling
 * under gravity with a lazy tumble — and gone before the counter finishes ticking.
 *
 * The pieces are drawn on one full-screen `Canvas` above the rest of the overlay, so they can
 * travel across the naming lines rather than being clipped to the halo's box. A `Canvas`
 * takes no input, so the overlay's tap-to-skip underneath still works.
 */
val CONFETTI_PALETTE: List<Color> = listOf(
    Color(0xFFFF6B6B), // coral
    Color(0xFFFFC94D), // gold
    Color(0xFF4DA3FF), // sky
    Color(0xFFB980FF), // lilac
    Color(0xFF3DDC97), // mint
    Color(0xFFFF7AB6), // pink
    Color(0xFFFF9F43), // tangerine
)

/** Gold, for the one label that wants to be warm rather than accent. */
val CONFETTI_GOLD: Color = CONFETTI_PALETTE[1]

private const val PIECE_COUNT = 110

/**
 * Fixed, not random per showing: the same reason the ring of motes is fixed (D33). A reveal
 * that scatters differently every time is a different screen every time, and one seed still
 * looks like a handful thrown in the air.
 */
private const val SEED = 0xB10DE

private class Piece(
    /** Launch direction, radians; the burst covers a fan above the horizontal. */
    val angle: Float,
    /** Launch speed as a fraction of the canvas height per unit progress. */
    val speed: Float,
    /** Long side in dp. */
    val size: Float,
    /** Full turns over the run, signed. */
    val spin: Float,
    /** Starting rotation and wobble offset, radians. */
    val phase: Float,
    /** Fraction of the run before this piece leaves the hand, so the burst has depth. */
    val delay: Float,
    val colour: Color,
    val round: Boolean,
)

private fun pieces(): List<Piece> {
    val rng = Random(SEED)
    return List(PIECE_COUNT) { i ->
        Piece(
            angle = (-PI * (0.08 + 0.84 * rng.nextDouble())).toFloat(),
            speed = 0.45f + 0.75f * rng.nextFloat(),
            size = 5f + 6f * rng.nextFloat(),
            spin = (if (rng.nextBoolean()) 1f else -1f) * (1.5f + 2.5f * rng.nextFloat()),
            phase = (2.0 * PI * rng.nextDouble()).toFloat(),
            delay = 0.12f * rng.nextFloat(),
            colour = CONFETTI_PALETTE[i % CONFETTI_PALETTE.size],
            round = rng.nextInt(4) == 0,
        )
    }
}

/** How hard the pieces fall, in canvas heights per unit progress squared. */
private const val GRAVITY = 1.35f

/** Sideways drift as the pieces fall, in dp of amplitude. */
private const val WOBBLE_DP = 9f

@Composable
fun ConfettiBurst(
    progress: Float,
    /** Where the pieces are thrown from, as fractions of the canvas. */
    originX: Float = 0.5f,
    originY: Float = 0.40f,
) {
    val pieces = remember { pieces() }
    if (progress <= 0f || progress >= 1f) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val origin = Offset(size.width * originX, size.height * originY)
        val wobble = WOBBLE_DP.dp.toPx()
        pieces.forEach { piece ->
            val t = ((progress - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach
            // Ballistic arc, measured in canvas heights so the throw scales with the screen.
            val reach = piece.speed * size.height
            val x = origin.x + cos(piece.angle) * reach * t * 0.9f +
                sin(t * 7f + piece.phase) * wobble * t
            val y = origin.y + sin(piece.angle) * reach * t + GRAVITY * size.height * t * t
            // Hold full colour through most of the fall, then thin out rather than vanish.
            val alpha = if (t < 0.7f) 1f else 1f - (t - 0.7f) / 0.3f
            val long = piece.size.dp.toPx()
            rotate(degrees = piece.spin * 360f * t + piece.phase * 57.3f, pivot = Offset(x, y)) {
                if (piece.round) {
                    drawCircle(piece.colour, radius = long * 0.42f, center = Offset(x, y), alpha = alpha)
                } else {
                    // A tumbling rectangle: the short side breathes with the spin, which is
                    // what sells a flat piece turning in the air.
                    val short = long * (0.35f + 0.3f * kotlin.math.abs(cos(t * 9f + piece.phase)))
                    drawRect(
                        color = piece.colour,
                        topLeft = Offset(x - long / 2f, y - short / 2f),
                        size = Size(long, short),
                        alpha = alpha,
                    )
                }
            }
        }
    }
}
