package dev.tlong.biodex.ui.reveal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.ui.common.NO_OWN_PHOTO_MARK
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme
import kotlinx.coroutines.delay

/**
 * Frame 4 of `mockup.html` (M09, D8). Not a route: a full-screen overlay the detail screen
 * shows when it is navigated with `justUnlocked = true` (ARCHITECTURE.md 6.1).
 *
 * DESIGN.md §4 sets the brief — the silhouette resolves into the user's photo, the number
 * stamps in, haptics fire, the counter reads its new value. Tappable to skip throughout.
 *
 * **D33 rebuilt this as a real sequence.** Two things were wrong. The animation never ran at
 * all: `animateFloatAsState(targetValue = 1f)` builds its animation already *at* 1f on first
 * composition, so every frame of the crossfade and the settle was skipped and the overlay was
 * a still card holding for a second and a half. And once that was fixed, the moment was still
 * thinner than the thing it marks, so the beats below were added.
 *
 * The order is deliberate, because a catch should read as an arrival rather than a fanfare:
 * the silhouette waits a beat and swells, the photograph resolves out of it as rings push
 * outward, the naming lines rise in one at a time, and the counter lands last with its own
 * haptic — the number moving is the point of the whole screen. Everything is drawn in the
 * accent on the app's own background: no confetti, no colour it does not already use, no
 * sound and no mascot, because D8's original reasoning still holds and this has to be worth
 * watching on the ninetieth unlock as much as the first.
 */
const val REVEAL_DURATION_MS = 2_600L

/** The beats, in milliseconds from the start. Each one reads as its own event. */
private const val HOLD_MS = 180L
private const val CROSSFADE_MS = 620
private const val BURST_MS = 900
private const val TEXT_MS = 520
private const val COUNTER_MS = 480

/** How long one text line takes to arrive, as a fraction of the staggered run. */
private const val LINE_SPAN = 0.55f

data class RevealContent(
    val commonName: String,
    val displayNumber: String,
    val scientificName: String?,
    val taxClass: TaxClass,
    /** S10: the counter names the list it incremented, so "4 / 80" is never ambiguous. */
    val kingdom: Kingdom,
    val silhouetteRes: String,
    /**
     * What the silhouette crossfades into. Normally the new capture's own thumbnail; for a
     * photoless plant (M41) it is the species' reference image, and null falls back to the
     * silhouette either way.
     */
    val thumbnailModel: String?,
    /**
     * §5.3's leaf, shown when this catch keeps no photo of the user's own. It marks the reveal
     * rather than the tile: the moment the silhouette becomes a picture is where the app can
     * say once, clearly, that this picture is the species and not the user's shot of it.
     */
    val leafMark: Boolean = false,
    val caughtCount: Int,
    val totalCount: Int,
    val whereAndWhen: String?,
)

/**
 * S10's counter. Each kingdom is its own life list (D13), so "47 / 120" alone would leave
 * the user working out which one just moved — the label says it: "4 / 80 plants".
 */
internal fun revealCounterLabel(
    content: RevealContent,
    /**
     * False for the fraction of a second before the counter ticks (D33): the line first shows
     * the count the user walked in with, so the increment is something they watch happen.
     * The total is never rolled back — only the catch is new, and on a user-added species the
     * denominator grew with it (D29), so a "before" total would be a number that never was.
     */
    showNewValue: Boolean = true,
): String {
    val noun = when (content.kingdom) {
        Kingdom.ANIMAL -> "animals"
        Kingdom.PLANT -> "plants"
        Kingdom.FUNGUS -> "fungi"
    }
    val shown = if (showNewValue) content.caughtCount else (content.caughtCount - 1).coerceAtLeast(0)
    return "$shown / ${content.totalCount} $noun"
}

@Composable
fun UnlockRevealOverlay(
    content: RevealContent,
    onDismiss: () -> Unit,
) {
    val colors = DexTheme.colors
    val haptics = LocalHapticFeedback.current

    // Four Animatables rather than one clock, because the beats overlap: the rings run across
    // the crossfade, and the text is still arriving as the counter starts. Each is remembered
    // at 0f and driven by the timeline below — never by `animateFloatAsState`, which would
    // start at its target and play nothing (the defect D33 fixes).
    val resolve = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val naming = remember { Animatable(0f) }
    val counter = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // A beat before anything moves. The silhouette is what the user has been looking at
        // on the grid for weeks, so the reveal is worth more if it starts from that.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(HOLD_MS)
        launch {
            burst.animateTo(1f, tween(durationMillis = BURST_MS, easing = LinearOutSlowInEasing))
        }
        resolve.animateTo(1f, tween(durationMillis = CROSSFADE_MS, easing = FastOutSlowInEasing))
        naming.animateTo(1f, tween(durationMillis = TEXT_MS, easing = LinearOutSlowInEasing))
        // The second tick lands on the number, which is the sentence the screen is here to
        // say: you have one more than you had.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        counter.animateTo(1f, tween(durationMillis = COUNTER_MS, easing = FastOutSlowInEasing))
        delay(REVEAL_DURATION_MS - HOLD_MS - CROSSFADE_MS - TEXT_MS - COUNTER_MS)
        onDismiss()
    }

    val progress = resolve.value

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            // Skippable: any tap ends it (DESIGN.md §4). No ripple — the overlay is the target.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(24.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(260.dp),
        ) {
            // The rings and motes are drawn outside the halo so they can travel past it.
            RevealBurst(progress = burst.value, colour = colors.accent)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    // 6.4's "soft accentSoft radial glow", as a flat halo — a real radial
                    // gradient would read as heavier than D8 wants.
                    .background(colors.accentSoft),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(colors.silBg)
                        // Swells past its resting size as the photograph lands, then settles.
                        // The overshoot is what makes the resolve read as an event rather
                        // than a dissolve.
                        .scale(0.94f + 0.10f * progress - 0.04f * progress * progress),
                ) {
                    SilhouetteIcon(
                        silhouetteRes = content.silhouetteRes,
                        taxClass = content.taxClass,
                        size = 104.dp,
                        tint = colors.sil,
                        modifier = Modifier.alpha(1f - progress),
                    )
                    if (content.thumbnailModel != null) {
                        AsyncImage(
                            model = content.thumbnailModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .alpha(progress),
                        )
                    }
                    if (content.leafMark) {
                        Text(
                            text = "🍃",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .alpha(progress),
                        )
                    }
                }
            }
        }
        if (content.leafMark) {
            Text(
                text = NO_OWN_PHOTO_MARK,
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
                modifier = Modifier.padding(top = 10.dp).alpha(progress),
            )
        }

        // The naming lines arrive one after another rather than together. Four labels landing
        // on the same frame is a block of text appearing; landing in sequence is the app
        // telling you what you caught.
        Text(
            text = "NEW SPECIES",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
            color = colors.accent,
            modifier = Modifier.padding(top = 22.dp).arrive(naming.value, 0),
        )
        Text(
            text = content.commonName,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.fg,
            modifier = Modifier.padding(top = 6.dp).arrive(naming.value, 1),
        )
        Text(
            text = listOfNotNull(content.displayNumber, content.scientificName)
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(top = 2.dp).arrive(naming.value, 2),
        )
        content.whereAndWhen?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
                modifier = Modifier.padding(top = 6.dp).arrive(naming.value, 3),
            )
        }
        // The counter is the only number on screen that changed, so it says so: it holds the
        // old value, flips to the new one, and swells as it does. S10's label is unchanged.
        val ticked = counter.value >= 0.5f
        Text(
            text = revealCounterLabel(content, showNewValue = ticked),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.accent,
            modifier = Modifier
                .padding(top = 16.dp)
                .alpha(0.35f + 0.65f * counter.value)
                .scale(1f + 0.22f * counterPop(counter.value)),
        )
        Text(
            text = "Tap to continue",
            style = MaterialTheme.typography.labelSmall,
            color = colors.faint,
            modifier = Modifier.padding(top = 10.dp).alpha(counter.value),
        )
    }
}

/**
 * One text line's entrance: it fades up and rises the last few pixels into place. [index] is
 * its place in the stagger, so line 2 starts moving when line 1 is most of the way there.
 */
private fun Modifier.arrive(progress: Float, index: Int): Modifier {
    val steps = 4
    val start = index * (1f - LINE_SPAN) / (steps - 1)
    val local = ((progress - start) / LINE_SPAN).coerceIn(0f, 1f)
    return alpha(local).offset(y = 8.dp * (1f - local))
}

/** A single swell, peaking as the number flips and returning to rest. */
private fun counterPop(progress: Float): Float =
    if (progress <= 0f || progress >= 1f) 0f else sin(progress * PI).toFloat()

/**
 * Two rings pushing outward and a scatter of small motes riding with them, all in the accent
 * at low alpha — the app's own colour, on its own background.
 *
 * This is the piece that had to be argued for hardest against D8's "deliberately quiet", and
 * the restraint is in the specifics: everything fades to nothing well before it reaches the
 * edge of the frame, the motes are one colour and never spin or bounce, and the whole burst
 * is over before the naming lines finish arriving. It reads as the moment landing, not as a
 * reward animation playing at you.
 */
@Composable
private fun RevealBurst(progress: Float, colour: Color) {
    if (progress <= 0f || progress >= 1f) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension / 2f

        // Rings: the second trails the first by a third of the run, so the burst has a pulse
        // rather than a single edge.
        listOf(0f, 0.33f).forEach { offset ->
            val local = ((progress - offset) / (1f - offset)).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) return@forEach
            drawCircle(
                color = colour,
                radius = base * (0.62f + 0.38f * local),
                center = centre,
                alpha = 0.28f * (1f - local),
                style = Stroke(width = (2.5f * (1f - local)).coerceAtLeast(0.5f).dp.toPx()),
            )
        }

        // Motes on a fixed ring of angles. Fixed, not random: a reveal that scatters
        // differently every time is a different screen every time, and this one is seen often.
        val fade = (1f - progress).coerceIn(0f, 1f)
        repeat(MOTE_COUNT) { i ->
            val angle = (2.0 * PI * i / MOTE_COUNT) + MOTE_TILT
            val distance = base * (0.66f + 0.42f * progress)
            drawCircle(
                color = colour,
                radius = (2.6f * fade).coerceAtLeast(0.4f).dp.toPx(),
                center = Offset(
                    x = centre.x + (cos(angle) * distance).toFloat(),
                    y = centre.y + (sin(angle) * distance).toFloat(),
                ),
                alpha = 0.5f * fade,
            )
        }
    }
}

private const val MOTE_COUNT = 10

/** Rotates the ring of motes off the axes, so none of them sits directly under the name. */
private const val MOTE_TILT = 0.31

@Preview(name = "Unlock reveal", widthDp = 380, heightDp = 780)
@Composable
private fun UnlockRevealPreview() {
    BioDexTheme {
        UnlockRevealOverlay(
            content = RevealContent(
                commonName = "Western Screech-Owl",
                displayNumber = "#021",
                scientificName = "Megascops kennicottii",
                taxClass = TaxClass.BIRD,
                kingdom = Kingdom.ANIMAL,
                silhouetteRes = "sil_bird",
                thumbnailModel = null,
                caughtCount = 47,
                totalCount = 120,
                whereAndWhen = "Aug 30, 2026",
            ),
            onDismiss = {},
        )
    }
}
