package dev.tlong.biodex.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.tlong.biodex.ui.theme.DexTheme

/** D80. Ticked by default; unticked marks the sighting as captive — a zoo, an aquarium. */
@Composable
fun WildToggle(wild: Boolean, onWildChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = wild, role = Role.Checkbox, onValueChange = onWildChange),
    ) {
        Checkbox(
            checked = wild,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted),
        )
        Text(
            text = if (wild) "Seen in the wild" else "Seen in captivity — a zoo or aquarium",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fg,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
