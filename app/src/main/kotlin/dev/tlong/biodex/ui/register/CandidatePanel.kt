package dev.tlong.biodex.ui.register

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tlong.biodex.data.identify.ResolvedCandidate
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * §5.2's candidate panel. Every rule it encodes is about *whose statement this is*: the
 * provider is named in the heading, the score is labelled as that provider's classifier
 * confidence, and nothing is preselected — a highlighted row would be the app making the
 * choice, which is exactly what D20 refuses.
 *
 * The three outcomes are visually distinct on purpose (M38). Candidates and no-candidates are
 * both ordinary answers and share the calm chrome; only "could not ask" is styled as an error,
 * and even that never blocks the catalogue list below it.
 */
fun LazyListScope.candidatePanel(
    identification: IdentificationState,
    selectedSpeciesId: String?,
    onPickCandidate: (ResolvedCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    when (identification) {
        IdentificationState.Idle -> Unit

        is IdentificationState.Running -> item(key = "identify-running") {
            PanelShell(heading = "${identification.provider} is looking…", onDismiss = null) {}
        }

        is IdentificationState.Done -> item(key = "identify-done") {
            PanelShell(
                heading = candidatePanelHeading(
                    provider = identification.provider,
                    shown = identification.candidates.size,
                    dropped = identification.dropped,
                ),
                onDismiss = onDismiss,
            ) {
                identification.candidates.forEach { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        scoreText = scoreLabel(candidate.candidate.score, identification.scoreKind),
                        selected = candidate.catalogueSpeciesId != null &&
                            candidate.catalogueSpeciesId == selectedSpeciesId,
                        onClick = { onPickCandidate(candidate) },
                    )
                }
                Caption(scoreCaption(identification.provider, identification.scoreKind))
                Caption(FALLBACKS)
            }
        }

        is IdentificationState.NoCandidates -> item(key = "identify-none") {
            PanelShell(
                heading = "${identification.provider} found no plant it recognises in this photo",
                onDismiss = onDismiss,
            ) {
                Caption(FALLBACKS)
            }
        }

        is IdentificationState.Failed -> item(key = "identify-failed") {
            val colors = DexTheme.colors
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.stopSoft)
                    .padding(12.dp),
            ) {
                Text(
                    text = identification.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.stop,
                )
                Text(
                    text = "Dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

/** S12: the two things that still work when the service has nothing to say. */
private const val FALLBACKS =
    "None of these? Type a name in the search box, or open the photo in Google Lens."

@Composable
private fun PanelShell(
    heading: String,
    onDismiss: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val colors = DexTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.rule, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = heading,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.fg,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.faint,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
        content()
    }
}

/**
 * One candidate. The dex number and "in dex" / "not in dex" are the whole of what the row adds
 * beyond the name: whether this suggestion is something the user can register right now, or
 * something they would be adding to their own catalogue.
 */
@Composable
private fun CandidateRow(
    candidate: ResolvedCandidate,
    scoreText: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentSoft else colors.bg)
            .border(
                1.dp,
                if (selected) colors.accent else colors.rule,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.commonName ?: candidate.scientificName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.scientificName,
                style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (candidate.inCatalogue) {
                    "in dex — tap to select"
                } else {
                    "not in dex — tap to add as your own species ＋"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (candidate.inCatalogue) colors.accent else colors.faint,
            )
        }
        if (scoreText != null) {
            Box(contentAlignment = Alignment.CenterEnd) {
                Text(
                    text = scoreText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.muted,
                )
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = DexTheme.colors.faint,
    )
}

/**
 * The action itself. A reason replaces the tap rather than accompanying it: M38 asks for the
 * specific reason inline, and a button that still looks pressable under an explanation of why
 * it will not work is the worst of both.
 */
@Composable
internal fun IdentifyButton(
    label: String,
    disabledReason: String?,
    onClick: () -> Unit,
) {
    val colors = DexTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (disabledReason == null) colors.accent else colors.faint,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (disabledReason == null) colors.accentSoft else colors.codeBg)
                .then(if (disabledReason == null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
        if (disabledReason != null) {
            Text(
                text = disabledReason,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
    }
}
