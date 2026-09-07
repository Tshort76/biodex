package dev.tlong.biodex.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.ui.addspecies.ConfirmSpeciesRoute
import dev.tlong.biodex.ui.detail.EntryDetailRoute
import dev.tlong.biodex.ui.grid.DexGridRoute
import dev.tlong.biodex.ui.nearest.NearestRoute
import dev.tlong.biodex.ui.photoviewer.PhotoViewerRoute
import dev.tlong.biodex.ui.register.RegisterRoute
import dev.tlong.biodex.ui.settings.LicensesRoute
import dev.tlong.biodex.ui.settings.SettingsRoute
import dev.tlong.biodex.ui.stats.StatsRoute
import dev.tlong.biodex.ui.theme.DexTheme
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Routes (ARCHITECTURE.md 6.1). Type-safe serializable route objects, no string
// templates. The Unlock Reveal is deliberately NOT a route: it is a full-screen
// overlay that EntryDetail shows when navigated with justUnlocked = true.
// ---------------------------------------------------------------------------

@Serializable
data object DexGrid

/**
 * [justUnlocked] plays the reveal (M09); [photoAdded] is the low-key counterpart for a repeat
 * registration — a brief "+1", because DESIGN.md §4 reserves ceremony for firsts so that
 * firsts stay special.
 */
@Serializable
data class EntryDetail(
    val speciesId: String,
    val justUnlocked: Boolean = false,
    val photoAdded: Boolean = false,
)

@Serializable
data class Register(
    val preselectedSpeciesId: String? = null,
    /**
     * M45. What a share handed us. These ride the route rather than being read from the intent
     * inside the screen, so the Register screen has one way in whatever opened it — and so the
     * back stack behaves: a share lands on the grid with Register above it, and Back leaves
     * the user in the app rather than throwing them out to whatever shared.
     */
    val sharedPhotoUri: String? = null,
    val sharedQuery: String? = null,
)

@Serializable
data class ConfirmSpecies(val draftId: String)

@Serializable
data class PhotoViewer(val captureId: String)

/**
 * D36. [speciesId] is null when opened from the grid's top bar, which anchors on the most
 * recent catch; non-null when opened from a species' own detail screen.
 */
@Serializable
data class Nearest(val speciesId: String? = null)

@Serializable
data object Stats

@Serializable
data object Settings

/** Reached from Settings; a route rather than a dialog because the text is long. */
@Serializable
data object Licenses

@Composable
fun BioDexNavHost(
    navController: NavHostController = rememberNavController(),
    /** M45. Non-null when the app was opened by a share rather than from the launcher. */
    intake: ShareIntake? = null,
) {
    val container = LocalContext.current.appContainer
    // The grid stays the start destination and Register is pushed on top of it, so Back from a
    // shared photo goes to the dex. Keyed on the intake so one share opens one screen; a second
    // share arrives as a new intent and a new instance, which is Android's default and right.
    LaunchedEffect(intake) {
        if (intake != null) {
            navController.navigate(
                Register(sharedPhotoUri = intake.photoUri, sharedQuery = intake.query),
            )
        }
    }
    NavHost(navController = navController, startDestination = DexGrid) {
        composable<DexGrid> {
            DexGridRoute(
                onOpenSpecies = { speciesId -> navController.navigate(EntryDetail(speciesId)) },
                onRegister = { navController.navigate(Register()) },
                onOpenStats = { navController.navigate(Stats) },
                onOpenNearest = { navController.navigate(Nearest()) },
                onOpenSettings = { navController.navigate(Settings) },
            )
        }
        composable<EntryDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<EntryDetail>()
            EntryDetailRoute(
                speciesId = route.speciesId,
                justUnlocked = route.justUnlocked,
                photoAdded = route.photoAdded,
                onBack = { navController.popBackStack() },
                onRegister = { speciesId -> navController.navigate(Register(speciesId)) },
                onOpenPhoto = { captureId -> navController.navigate(PhotoViewer(captureId)) },
                onOpenNearest = { navController.navigate(Nearest(route.speciesId)) },
                // M20: a details-pending entry opened online looks itself up and presents the
                // same confirmation card. Single-top, so a second emission cannot stack cards.
                onBackfillReady = { draftId ->
                    navController.navigate(ConfirmSpecies(draftId)) { launchSingleTop = true }
                },
            )
        }
        composable<Register> { backStackEntry ->
            val route = backStackEntry.toRoute<Register>()
            RegisterRoute(
                preselectedSpeciesId = route.preselectedSpeciesId,
                shared = shareIntakeOf(route),
                onBack = { navController.popBackStack() },
                onRegistered = { speciesId, justUnlocked ->
                    // DESIGN.md §6's navigation rule: after registering, back from the detail
                    // screen returns to the grid, not to the Register screen.
                    navController.navigate(
                        EntryDetail(
                            speciesId = speciesId,
                            justUnlocked = justUnlocked,
                            photoAdded = !justUnlocked,
                        ),
                    ) {
                        popUpTo(DexGrid)
                    }
                },
                // `prefetched` is null on the typed-name path and carries the GBIF lookup an
                // identification already ran, so a candidate that is not in the catalogue
                // opens the same confirmation card rather than a second one (M33).
                onAddOwnSpecies = { typedName, photoUri, photoSource, prefetched ->
                    val draftId = container.addSpeciesDrafts.put(
                        typedName = typedName,
                        photoUri = photoUri,
                        photoSource = photoSource,
                        prefetched = prefetched,
                    )
                    navController.navigate(ConfirmSpecies(draftId))
                },
            )
        }
        composable<ConfirmSpecies> { backStackEntry ->
            val route = backStackEntry.toRoute<ConfirmSpecies>()
            ConfirmSpeciesRoute(
                draftId = route.draftId,
                onBack = { navController.popBackStack() },
                // A new user-added species is a first catch by definition, so it gets the
                // reveal — and back from the detail screen returns to the grid (DESIGN.md §6).
                onCreated = { speciesId ->
                    navController.navigate(
                        EntryDetail(speciesId = speciesId, justUnlocked = true),
                    ) {
                        popUpTo(DexGrid)
                    }
                },
                // A backfill only filled in an entry that already exists; going back to it is
                // the whole of the outcome.
                onUpdated = { navController.popBackStack() },
            )
        }
        composable<PhotoViewer> { backStackEntry ->
            val route = backStackEntry.toRoute<PhotoViewer>()
            PhotoViewerRoute(
                captureId = route.captureId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Nearest> { backStackEntry ->
            NearestRoute(
                speciesId = backStackEntry.toRoute<Nearest>().speciesId,
                onBack = { navController.popBackStack() },
                // Single-top, so walking a chain of neighbours cannot stack a hundred copies
                // of this screen behind the user.
                onOpenSpecies = { speciesId ->
                    navController.navigate(EntryDetail(speciesId)) { launchSingleTop = true }
                },
            )
        }
        composable<Stats> {
            StatsRoute(
                // The bottom bar's Dex tab and the back arrow are the same move: Stats is
                // always reached from the grid, so popping returns exactly where the user was.
                onBack = { navController.popBackStack() },
                onOpenSpecies = { speciesId -> navController.navigate(EntryDetail(speciesId)) },
            )
        }
        composable<Settings> {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenLicenses = { navController.navigate(Licenses) },
            )
        }
        composable<Licenses> { LicensesRoute(onBack = { navController.popBackStack() }) }
    }
}

/**
 * What a route shows until its own slice replaces it. Nothing lands here any more: slice 8
 * replaced the last two placeholders (Stats and Settings). Kept as the shape a future route
 * starts from.
 */
@Suppress("unused")
@Composable
private fun Placeholder(
    title: String,
    detail: String? = null,
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
    }
}

/** The share payload a [Register] route is carrying, or null when it was opened normally. */
private fun shareIntakeOf(route: Register): ShareIntake? =
    ShareIntake(photoUri = route.sharedPhotoUri, query = route.sharedQuery)
        .takeIf { it.hasSomething }
