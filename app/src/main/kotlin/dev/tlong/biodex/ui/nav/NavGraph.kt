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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import dev.tlong.biodex.ui.addspecies.DraftPhoto
import dev.tlong.biodex.ui.capture.PickedPhoto
import dev.tlong.biodex.ui.capture.rememberGalleryPicker
import dev.tlong.biodex.ui.identify.IdentifyRoute
import dev.tlong.biodex.ui.detail.EntryDetailRoute
import dev.tlong.biodex.ui.grid.DexGridRoute
import dev.tlong.biodex.ui.nearest.NearestRoute
import dev.tlong.biodex.ui.photoviewer.PhotoViewerRoute
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

/** D77: the grid's saved-state keys for a return from a capture. */
private const val SCROLL_TO_KEY = "scrollToSpeciesId"
private const val NOTE_KEY = "arrivalNote"

@Serializable
data object DexGrid

/**
 * [justUnlocked] plays the reveal (M09) for a first catch made on another screen, and
 * [homeAfterReveal] then returns to the grid scrolled to the species (D77), since that capture
 * is finished.
 */
@Serializable
data class EntryDetail(
    val speciesId: String,
    val justUnlocked: Boolean = false,
    val homeAfterReveal: Boolean = false,
    /** D76: open the photo picker on arrival, as "Yes — register my photo" promised. */
    val capture: Boolean = false,
)

/** D78: a photo picked from the grid's ＋, to be named and captured. */
@Serializable
data class Identify(val photoUri: String, val displayName: String? = null)

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
fun BioDexNavHost(navController: NavHostController = rememberNavController()) {
    val container = LocalContext.current.appContainer
    // D69: adding a species by name from the grid's search opens the lookup card with that name.
    val addSpecies = { name: String ->
        navController.navigate(ConfirmSpecies(container.addSpeciesDrafts.put(typedName = name)))
    }
    // D77: home to the grid, scrolled to the species just captured, from wherever the capture
    // was made.
    val homeTo = { speciesId: String, note: String? ->
        navController.getBackStackEntry<DexGrid>().savedStateHandle.apply {
            set(SCROLL_TO_KEY, speciesId)
            set(NOTE_KEY, note)
        }
        navController.popBackStack(DexGrid, inclusive = false)
    }
    // A first catch made off the entry screen plays its reveal there, then goes home (D77, D78).
    val revealThenHome = { speciesId: String ->
        navController.navigate(EntryDetail(speciesId, justUnlocked = true, homeAfterReveal = true)) {
            popUpTo(DexGrid)
        }
    }
    NavHost(navController = navController, startDestination = DexGrid) {
        composable<DexGrid> { backStackEntry ->
            // D77: what a capture on an entry hands back — the species to scroll to, and a note.
            val handle = backStackEntry.savedStateHandle
            val scrollTo by handle.getStateFlow<String?>(SCROLL_TO_KEY, null).collectAsState()
            val note by handle.getStateFlow<String?>(NOTE_KEY, null).collectAsState()
            // D78: the ＋ picks a photo first; the Identify screen names it.
            val pickPhoto = rememberGalleryPicker { photo ->
                navController.navigate(Identify(photo.uri, photo.displayName))
            }
            DexGridRoute(
                scrollToSpeciesId = scrollTo,
                note = note,
                onArrivalHandled = {
                    handle[SCROLL_TO_KEY] = null
                    handle[NOTE_KEY] = null
                },
                onOpenSpecies = { speciesId -> navController.navigate(EntryDetail(speciesId)) },
                onAddPhoto = pickPhoto,
                onAddSpecies = addSpecies,
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
                homeAfterReveal = route.homeAfterReveal,
                startCapture = route.capture,
                onBack = { navController.popBackStack() },
                onOpenPhoto = { captureId -> navController.navigate(PhotoViewer(captureId)) },
                onOpenNearest = { navController.navigate(Nearest(route.speciesId)) },
                // M20: a details-pending entry opened online looks itself up and presents the
                // same confirmation card. Single-top, so a second emission cannot stack cards.
                onBackfillReady = { draftId ->
                    navController.navigate(ConfirmSpecies(draftId)) { launchSingleTop = true }
                },
                // D77: captured here, so home — scrolled to it — like "← Dex", from wherever
                // the entry was opened.
                onCaptured = { note -> homeTo(route.speciesId, note) },
            )
        }
        composable<Identify> { backStackEntry ->
            val route = backStackEntry.toRoute<Identify>()
            IdentifyRoute(
                photo = PickedPhoto(route.photoUri, route.displayName),
                onBack = { navController.popBackStack() },
                onCaptured = { speciesId, isFirst ->
                    if (isFirst) revealThenHome(speciesId) else homeTo(speciesId, "+1 photo")
                },
                // Q02: a name the dex lacks is added and captured with this photo in one go.
                onAdd = { name, photoUri, place ->
                    navController.navigate(
                        ConfirmSpecies(
                            container.addSpeciesDrafts.put(
                                typedName = name,
                                photo = DraftPhoto(photoUri, place),
                            ),
                        ),
                    )
                },
            )
        }
        composable<ConfirmSpecies> { backStackEntry ->
            val route = backStackEntry.toRoute<ConfirmSpecies>()
            ConfirmSpeciesRoute(
                draftId = route.draftId,
                onBack = { navController.popBackStack() },
                // D69. Adding is not catching. "Not yet" is home. "Yes" is the entry, uncaught, with the
                // photo picker already open (D76); the catch plays the reveal there, and back
                // from it returns to the grid (DESIGN.md §6).
                onNotCaught = { navController.popBackStack(DexGrid, inclusive = false) },
                onCaught = { speciesId ->
                    navController.navigate(EntryDetail(speciesId = speciesId, capture = true)) {
                        popUpTo(DexGrid)
                    }
                },
                // A backfill only filled in an entry that already exists; going back to it is
                // the whole of the outcome.
                onAddedAndCaptured = revealThenHome,
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
