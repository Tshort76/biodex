package dev.tlong.biodex.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tlong.biodex.domain.SpeciesUse
import dev.tlong.biodex.domain.UsesNote
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * A species' uses (M24, D14, D15, D48, S09) — `mockup.html` frame 7's `.uses` block.
 *
 * **The order on screen is the whole point of this file.** The claims here are not equally
 * trustworthy, so they are ranked by how much weight the reader should put on them, most
 * cautious first:
 *
 *  1. the **caution**, in the stop colour with a warning glyph, because a lookalike or a
 *     preparation hazard must never read as visually equal to a use;
 *  2. the **curated note** in body text — the app's own editorial claim;
 *  3. the **disclaimer** (M30), which is on every uses section without exception.
 *
 * A species with **nothing to say** — no tag and no caution — renders nothing at all: the
 * caller does not draw this, and there is no empty section. That is D15, and it is why
 * [UsesContent] carries no "empty" state. Every cautioned fungus reaches here on the caution
 * alone (M35); an edible animal on the tag alone (D48). The sourced Duke's line that once sat
 * between the note and the disclaimer left with the plants (D59).
 */
data class UsesContent(
    val uses: Set<SpeciesUse>,
    val usesNote: String?,
)

/**
 * M30. The app's only claim is that a use is *documented for the species*; it never says a
 * part is safe and never says the photograph is that species (D2). One short line, carried
 * by every screen that shows a use — the longer statement belongs in the README, not in
 * front of someone playing a collecting game.
 */
const val USES_DISCLAIMER =
    "Documented uses of the species — not advice. Do your own research before eating or " +
        "using anything."

@Composable
fun UsesSection(content: UsesContent, modifier: Modifier = Modifier) {
    val colors = DexTheme.colors
    val (body, caution) = UsesNote.cautionSplit(content.usesNote)

    // A section holding one warning and no uses is not a uses section. Every fungus lands here.
    val hasUses = content.uses.isNotEmpty()
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(if (hasUses) "Uses" else "Caution")
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.codeBg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // Ordered by the enum, not by the set — and skipped entirely when there are none,
            // because a species can reach this section on a caution alone.
            val tags = SpeciesUse.entries.filter { it in content.uses }
            if (tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { use -> UseTag(use) }
                }
            }

            caution?.let {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.stopSoft)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "⚠",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.stop,
                    )
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.stop,
                    )
                }
            }

            if (body.isNotBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fg,
                )
            }

        }
        // The disclaimer is about *documented uses*, so it appears only where there are
        // some. A caution-only section — every fungus — is one warning sentence, and following
        // it with "documented uses of the species" when none are shown says nothing and
        // dilutes the line above it.
        if (hasUses) {
            AttributionLine(text = USES_DISCLAIMER, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** `.uses .tag` — a **filled** pill, unlike the outlined filter chips: edible in `ok`. */
@Composable
private fun UseTag(use: SpeciesUse) {
    val colors = DexTheme.colors
    val fill: Color = when (use) {
        SpeciesUse.EDIBLE -> colors.ok
    }
    Text(
        text = when (use) {
            SpeciesUse.EDIBLE -> "Food source"
        },
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = colors.card,
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
