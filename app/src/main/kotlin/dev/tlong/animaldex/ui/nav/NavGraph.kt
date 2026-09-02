package dev.tlong.animaldex.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.tlong.animaldex.ui.theme.DexTheme
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Routes (ARCHITECTURE.md 6.1). Type-safe serializable route objects, no string
// templates. The Unlock Reveal is deliberately NOT a route: it is a full-screen
// overlay that EntryDetail shows when navigated with justUnlocked = true.
// ---------------------------------------------------------------------------

@Serializable
data object DexGrid

@Serializable
data class EntryDetail(val speciesId: String, val justUnlocked: Boolean = false)

@Serializable
data class Register(val preselectedSpeciesId: String? = null)

@Serializable
data class ConfirmSpecies(val draftId: String)

@Serializable
data class PhotoViewer(val captureId: String)

@Serializable
data object Stats

@Serializable
data object Settings

@Composable
fun AnimalDexNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = DexGrid) {
        composable<DexGrid> {
            Placeholder(
                title = "Dex Grid — coming soon",
                links = listOf(
                    "Entry Detail" to { navController.navigate(EntryDetail("western-screech-owl")) },
                    "Unlock Reveal" to {
                        navController.navigate(EntryDetail("western-screech-owl", justUnlocked = true))
                    },
                    "Register" to { navController.navigate(Register()) },
                    "Confirm Species" to { navController.navigate(ConfirmSpecies("draft-0")) },
                    "Photo Viewer" to { navController.navigate(PhotoViewer("capture-0")) },
                    "Stats" to { navController.navigate(Stats) },
                    "Settings" to { navController.navigate(Settings) },
                ),
            )
        }
        composable<EntryDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<EntryDetail>()
            val reveal = if (route.justUnlocked) " (unlock reveal)" else ""
            Placeholder(title = "Entry Detail — coming soon", detail = route.speciesId + reveal)
        }
        composable<Register> { backStackEntry ->
            val route = backStackEntry.toRoute<Register>()
            Placeholder(
                title = "Register a Species — coming soon",
                detail = route.preselectedSpeciesId ?: "no species preselected",
            )
        }
        composable<ConfirmSpecies> { backStackEntry ->
            val route = backStackEntry.toRoute<ConfirmSpecies>()
            Placeholder(title = "Add Species — Confirm — coming soon", detail = route.draftId)
        }
        composable<PhotoViewer> { backStackEntry ->
            val route = backStackEntry.toRoute<PhotoViewer>()
            Placeholder(title = "Photo Viewer — coming soon", detail = route.captureId)
        }
        composable<Stats> { Placeholder(title = "Stats — coming soon") }
        composable<Settings> { Placeholder(title = "Settings — coming soon") }
    }
}

/**
 * The only composable slice 1 ships. Every route renders it until its own slice
 * replaces the route body; the buttons exist so the walking skeleton exercises
 * type-safe navigation on the phone.
 */
@Composable
private fun Placeholder(
    title: String,
    detail: String? = null,
    links: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = DexTheme.colors.fg,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = DexTheme.colors.faint,
            )
        }
        links.forEach { (label, onClick) ->
            OutlinedButton(onClick = onClick) { Text(label) }
        }
    }
}
