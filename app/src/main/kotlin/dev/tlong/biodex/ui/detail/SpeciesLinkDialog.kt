package dev.tlong.biodex.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.tlong.biodex.domain.normalizedWebLink
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * D103. The owner's own page for a species — an iNaturalist taxon, a regional field guide —
 * for the many with no Wikipedia article. Saved blank, it goes back to the catalogue's link.
 */
@Composable
internal fun SpeciesLinkDialog(
    ownUrl: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DexTheme.colors
    var text by rememberSaveable { mutableStateOf(ownUrl.orEmpty()) }
    val removes = text.isBlank() && ownUrl != null
    val ready = removes || normalizedWebLink(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = {
            Text(
                text = "Link a web page",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.fg,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (ownUrl != null) {
                        "Paste a page about this species. Clear it to go back to the usual link."
                    } else {
                        "Paste a page about this species — iNaturalist, a field guide, anything."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.codeBg)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = "inaturalist.org/taxa/…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.faint,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fg),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = ready) {
                Text(
                    text = if (removes) "Remove" else "Save",
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
