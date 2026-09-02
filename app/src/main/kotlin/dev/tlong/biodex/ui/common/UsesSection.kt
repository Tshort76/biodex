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
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.UsesNote
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * A plant's uses (M24, D14, D15, S09) — `mockup.html` frame 7's `.uses` block, which takes
 * the place of the call row a plant does not have.
 *
 * **The order on screen is the whole point of this file.** Three kinds of claim sit here and
 * they are not equally trustworthy, so they are ranked by how much weight the reader should
 * put on them, most cautious first:
 *
 *  1. the **caution**, in the stop colour with a warning glyph, because a lookalike or a
 *     preparation hazard must never read as visually equal to "berries, late summer";
 *  2. the **curated note** in body text — the app's own editorial claim about part and season;
 *  3. the **sourced Duke's line** in the muted attribution register, so a reader can see at a
 *     glance that this sentence came from a database and the one above it did not;
 *  4. the **disclaimer** (M30), which is on every uses section without exception.
 *
 * A plant with **nothing to say** — no tags and no caution — renders nothing at all: the
 * caller does not draw this, and there is no empty section. That is D15, and it is why
 * [UsesContent] carries no "empty" state.
 *
 * A caution can arrive with no tags beside it. `keptUsesNote` keeps a `Caution:` sentence when
 * a plant's uses are empty and drops the rest of the note, which is how Western Wild Ginger
 * warns about aristolochic acid while carrying neither Edible nor Medicinal. In that shape the
 * section is the caution and the disclaimer, with no tag row and no note body above it.
 */
data class UsesContent(
    val uses: Set<PlantUse>,
    val usesNote: String?,
    val medicinalActivities: List<String>,
    val medicinalRecordCount: Int,
    val usesAttribution: String?,
)

/**
 * M30, verbatim. The app's only claim is that a use is *documented for the species*; it never
 * says a part is safe and never says the photograph is that species (D2).
 */
const val USES_DISCLAIMER =
    "Documented uses of the species — not identification, not safety advice. Never eat or " +
        "use a plant on the strength of this app."

/**
 * The sourced half, as one line: "Duke's records 105 traditional uses: astringent, diuretic,
 * wound". Null when Duke's has nothing to say, which is an ordinary state for about a fifth
 * of species — the section then carries the curated half alone.
 */
fun dukesLine(recordCount: Int, activities: List<String>): String? {
    if (recordCount <= 0) return null
    val noun = if (recordCount == 1) "traditional use" else "traditional uses"
    val named = activities.filter { it.isNotBlank() }
    val tail = if (named.isEmpty()) "" else ": " + named.joinToString(", ") { it.lowercase() }
    return "Duke's records $recordCount $noun$tail"
}

/** The disclaimer plus the source's own credit line, when there is a source to credit. */
fun usesDisclaimer(usesAttribution: String?): String =
    if (usesAttribution.isNullOrBlank()) {
        USES_DISCLAIMER
    } else {
        "$USES_DISCLAIMER Medicinal: $usesAttribution."
    }

@Composable
fun UsesSection(content: UsesContent, modifier: Modifier = Modifier) {
    val colors = DexTheme.colors
    val (body, caution) = UsesNote.cautionSplit(content.usesNote)
    val source = dukesLine(content.medicinalRecordCount, content.medicinalActivities)

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader("Uses")
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.codeBg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // Ordered by the enum, not by the set, so two plants never disagree about which
            // tag comes first — and skipped entirely when there are none, because a plant can
            // reach this section on a caution alone.
            val tags = PlantUse.entries.filter { it in content.uses }
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

            source?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    ),
                    color = colors.muted,
                )
            }
        }
        AttributionLine(
            text = usesDisclaimer(content.usesAttribution),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * `.uses .tag` — a **filled** pill, unlike the outlined filter chips: edible in `ok`,
 * medicinal in `accent`. The colour split is the same one the rest of the app uses for
 * "the app's own claim" versus "a source's claim".
 */
@Composable
private fun UseTag(use: PlantUse) {
    val colors = DexTheme.colors
    val fill: Color = when (use) {
        PlantUse.EDIBLE -> colors.ok
        PlantUse.MEDICINAL -> colors.accent
    }
    Text(
        text = when (use) {
            PlantUse.EDIBLE -> "Edible"
            PlantUse.MEDICINAL -> "Medicinal"
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
