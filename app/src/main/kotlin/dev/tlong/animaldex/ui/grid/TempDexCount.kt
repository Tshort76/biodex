package dev.tlong.animaldex.ui.grid

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tlong.animaldex.appContainer
import dev.tlong.animaldex.domain.DexProgress
import dev.tlong.animaldex.ui.theme.DexTheme

/**
 * Scaffolding, not a screen. Slice 3's done-gate is that the first phone connection proves
 * the whole chain — asset → importer → Room → repository flow → Compose — so the
 * placeholder grid route reads the real counts out of the database. Slice 4 deletes this
 * file when it builds the actual grid header.
 */
@Composable
fun TempDexCountLine() {
    val context = LocalContext.current
    val repository = remember(context) { context.appContainer.dexRepository }
    val progress by repository.dexProgress()
        .collectAsStateWithLifecycle(initialValue = DexProgress.Empty)

    Text(
        text = "${progress.totalSpecies} species / ${progress.caughtCount} caught",
        style = MaterialTheme.typography.labelLarge,
        color = DexTheme.colors.accent,
    )
}
