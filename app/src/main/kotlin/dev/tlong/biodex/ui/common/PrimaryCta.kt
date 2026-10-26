package dev.tlong.biodex.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * The filled accent button a screen has one of: its commit. Dimmed and inert when [enabled] is false.
 * A [fill] replaces the accent while enabled, with dark text so it reads on the light colours.
 */
@Composable
fun PrimaryCta(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Brush? = null,
) {
    val colors = DexTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (enabled && fill != null) Modifier.background(fill)
                else Modifier.background(if (enabled) colors.accent else colors.rule),
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = when {
                !enabled -> colors.faint
                fill != null -> RAINBOW_LABEL
                else -> colors.card
            },
        )
    }
}

/** Near-black, since the rainbow's gold and mint are too light to carry white text. */
private val RAINBOW_LABEL = Color(0xFF1C1B1F)
