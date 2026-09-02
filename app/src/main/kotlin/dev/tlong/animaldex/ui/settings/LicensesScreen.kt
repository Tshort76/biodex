package dev.tlong.animaldex.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tlong.animaldex.ui.theme.DexTheme
import java.io.IOException

/**
 * The licenses screen, rendered from the hand-kept `assets/licenses.md`.
 *
 * Hand-kept rather than generated: this app has a dozen dependencies and three data sources,
 * and the sources are the part that actually needs stating — CC BY-SA text and images carry
 * an attribution obligation that no Gradle license plugin knows about.
 *
 * The renderer understands exactly what the file uses: `#`/`##` headings, `-` bullets, and
 * paragraphs. It is not a Markdown implementation and does not want to be.
 */
@Composable
fun LicensesRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        try {
            context.assets.open(LICENSES_ASSET).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            "The licenses file could not be read."
        }
    }
    LicensesScreen(text = text, onBack = onBack)
}

@Composable
fun LicensesScreen(text: String, onBack: () -> Unit) {
    val colors = DexTheme.colors
    Scaffold(containerColor = colors.bg) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.muted,
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Text(
                    text = "Licenses",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.fg,
                )
            }
            text.lines().forEach { line -> MarkdownLine(line) }
            Box(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MarkdownLine(line: String) {
    val colors = DexTheme.colors
    val trimmed = line.trim()
    when {
        trimmed.isEmpty() -> Box(modifier = Modifier.height(6.dp))

        trimmed.startsWith("## ") -> Text(
            text = trimmed.removePrefix("## "),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.fg,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )

        trimmed.startsWith("# ") -> Text(
            text = trimmed.removePrefix("# "),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.fg,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )

        trimmed.startsWith("- ") -> Text(
            text = "·  " + trimmed.removePrefix("- "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(start = 6.dp),
        )

        else -> Text(
            text = trimmed,
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
    }
}

const val LICENSES_ASSET = "licenses.md"
