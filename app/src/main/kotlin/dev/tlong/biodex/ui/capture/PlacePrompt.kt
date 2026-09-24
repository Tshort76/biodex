package dev.tlong.biodex.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tlong.biodex.domain.GazetteerPlace
import dev.tlong.biodex.domain.PlaceAnswer
import dev.tlong.biodex.domain.canonicalPlace
import dev.tlong.biodex.domain.suggestPlaces
import dev.tlong.biodex.ui.theme.DexTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * What every capture shares, wherever it starts (D76, D78): the photo it carries, and the
 * "Where was this?" prompt raised when that photo has no place (D64, D68).
 */

/** A photo picked for a capture, before anything has been written. */
data class PickedPhoto(
    val uri: String,
    val displayName: String? = null,
)

/**
 * D68. The place prompt's own little screen state: what has been typed, the labels to offer
 * for it, and whether what is typed is one of them.
 */
data class PlaceSearchState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    /**
     * The place what is typed names, with the list's own spelling and its point — null when
     * neither list holds it, which is a perfectly good answer (D68: autocomplete, not a gate).
     */
    val canonical: PlaceAnswer? = null,
) {
    /**
     * What a registration would write: the list's place when what was typed names one, and
     * otherwise the text as typed with no coordinates. Null only when nothing has been typed at
     * all, which is the one state the prompt refuses — D60 still needs *a* place.
     */
    val answer: PlaceAnswer?
        get() = canonical ?: query.trim().takeIf { it.isNotEmpty() }?.let { PlaceAnswer(it) }

    /** Whether what is typed came off one of the two lists, for the row that draws as chosen. */
    val isKnown: Boolean get() = canonical != null
}

/**
 * D68. What the prompt's Capture tap writes for [typed]. The search runs a keystroke behind
 * the field — the dialog owns the text, the ViewModel only hears about it — so the search's
 * answer is used only when it was computed for exactly this text. Otherwise the text is taken
 * as typed: a tap faster than one search is rare, and it costs a listed place its spelling and
 * point, never the registration.
 */
fun placeAnswerFor(typed: String, search: PlaceSearchState): PlaceAnswer? {
    if (typed.isBlank()) return null
    if (search.query == typed) return search.answer
    return PlaceAnswer(typed.trim())
}

/**
 * D68. Pure, and kept out of any screen's state function: it scans 45,000 names on every
 * keystroke, which is microseconds but belongs on a background dispatcher, and the ViewModel
 * puts it there.
 */
fun placeSearchState(
    query: Flow<String>,
    gazetteer: Flow<List<GazetteerPlace>>,
    recent: Flow<List<String>>,
): Flow<PlaceSearchState> =
    combine(query, gazetteer, recent) { typed, places, used ->
        PlaceSearchState(
            query = typed,
            suggestions = suggestPlaces(typed, places, used),
            canonical = canonicalPlace(typed, places, used),
        )
    }

/**
 * D64/D68. Raised by a capture when the photo carries no coordinates, and only then. Material's dialog, as in Settings (D49), with a suggestion list under the
 * field: the places this collection already uses, then the region's bundled gazetteer. The
 * list is autocomplete rather than a gate — tapping a row fills the field with that place's
 * own spelling, and anything else typed is taken as written.
 */
@Composable
fun PlacePromptDialog(
    place: PlaceSearchState,
    onQueryChange: (String) -> Unit,
    onPlaceEntered: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DexTheme.colors
    // The field's text lives here, not in the ViewModel. Routed through the ViewModel it came
    // back asynchronously, after the search; in between, the field redrew with the old value
    // and the keyboard's input was thrown away — every keystroke, on the phone. The ViewModel
    // is told what was typed and answers with suggestions; it never owns the text.
    var text by rememberSaveable { mutableStateOf("") }
    val type = { value: String ->
        text = value
        onQueryChange(value)
    }
    val ready = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = {
            Text(
                text = "Where was this?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.fg,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "The photo carries no location. Pick a place, or type your own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.codeBg)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(text = "📍", style = MaterialTheme.typography.bodyMedium)
                    Box(modifier = Modifier.weight(1f)) {
                        if (text.isEmpty()) {
                            Text(
                                text = "e.g. Bear Valley, Point Reyes",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.faint,
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = type,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fg),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // Capped rather than scrolled to its content: a dialog that grows as you type
                // walks its own buttons off the bottom of a short screen.
                LazyColumn(modifier = Modifier.heightIn(max = 208.dp)) {
                    items(place.suggestions, key = { it }) { suggestion ->
                        PlaceSuggestionRow(
                            label = suggestion,
                            chosen = suggestion == place.canonical?.label,
                            onClick = { type(suggestion) },
                        )
                    }
                    // A picked place's full label matches no name on the list, so the
                    // suggestions empty out the moment one is chosen — show the choice itself
                    // rather than a line claiming nothing matched.
                    val chosen = place.canonical?.label?.takeIf { place.query == text }
                    if (place.suggestions.isEmpty() && chosen != null) {
                        item {
                            PlaceSuggestionRow(label = "✓ $chosen", chosen = true, onClick = {})
                        }
                    }
                    if (place.suggestions.isEmpty() && chosen == null && text.isNotBlank() &&
                        place.query == text
                    ) {
                        item {
                            Text(
                                text = "Nothing on the list matches — what you typed will be " +
                                    "used as it is.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.faint,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPlaceEntered(text) }, enabled = ready) {
                Text(
                    text = "Capture",
                    color = if (ready) colors.accent else colors.faint,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", color = colors.muted)
            }
        },
    )
}

/** One offered place. The tap fills the field, which is also what makes the button live. */
@Composable
private fun PlaceSuggestionRow(label: String, chosen: Boolean, onClick: () -> Unit) {
    val colors = DexTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (chosen) colors.accent else colors.fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}
