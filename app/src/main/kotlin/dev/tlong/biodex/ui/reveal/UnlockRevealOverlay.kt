package dev.tlong.biodex.ui.reveal

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
 * DESIGN.md §4 sets the whole brief — the silhouette resolves into the user's photo, the
 * number stamps in, one haptic tick fires, the counter reads its new value. About a second and
 * a half, tappable to skip, and deliberately quiet: this has to still feel good on the 90th
 * unlock, so there is no confetti, no sound and no mascot.
 */
const val REVEAL_DURATION_MS = 1_500L
private const val CROSSFADE_MS = 700

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
internal fun revealCounterLabel(content: RevealContent): String {
    val noun = when (content.kingdom) {
        Kingdom.ANIMAL -> "animals"
        Kingdom.PLANT -> "plants"
        Kingdom.FUNGUS -> "fungi"
    }
    return "${content.caughtCount} / ${content.totalCount} $noun"
}

@Composable
fun UnlockRevealOverlay(
    content: RevealContent,
    onDismiss: () -> Unit,
) {
    val colors = DexTheme.colors
    val haptics = LocalHapticFeedback.current

    // Drives both halves of the cross-fade and the settle from 0.96, so nothing can drift
    // apart mid-animation.
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = CROSSFADE_MS, easing = LinearEasing),
        label = "reveal",
    )

    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(REVEAL_DURATION_MS)
        onDismiss()
    }

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
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                // 6.4's "soft accentSoft radial glow", as a flat halo — a real radial gradient
                // would read as heavier than D8 wants.
                .background(colors.accentSoft),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(colors.silBg)
                    .scale(0.96f + 0.04f * progress),
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
        if (content.leafMark) {
            Text(
                text = NO_OWN_PHOTO_MARK,
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
                modifier = Modifier.padding(top = 10.dp).alpha(progress),
            )
        }

        Text(
            text = "NEW SPECIES",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
            color = colors.accent,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            text = content.commonName,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.fg,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = listOfNotNull(content.displayNumber, content.scientificName)
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(top = 2.dp),
        )
        content.whereAndWhen?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            text = revealCounterLabel(content),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.accent,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Tap to continue",
            style = MaterialTheme.typography.labelSmall,
            color = colors.faint,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

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
