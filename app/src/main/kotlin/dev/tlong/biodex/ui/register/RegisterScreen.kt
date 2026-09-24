package dev.tlong.biodex.ui.register

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.data.photo.hasPhotoLibraryAccess
import dev.tlong.biodex.data.photo.photoLibraryPermissions
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme
import kotlinx.coroutines.flow.first

/**
 * Frame 3 of `mockup.html` (M07, M08, M10, S06). Species-first: search the catalogue offline,
 * attach one photo and register.
 *
 * A photo is one photo, from the gallery or the Files picker (D58), and every
 * kingdom keeps it. (Pl@ntNet identification and the photoless plant catch lived here from
 * v6 to v20; both left with the plants, D59.)
 */
@Composable
fun RegisterRoute(
    preselectedSpeciesId: String?,
    /** D69: the grid's search text, already typed into this screen's search. */
    initialQuery: String? = null,
    onBack: () -> Unit,
    onRegistered: (speciesId: String, justUnlocked: Boolean) -> Unit,
    /** D69: the no-results line's way to add the typed name to the dex. */
    onAddSpecies: (name: String) -> Unit,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: RegisterViewModel = viewModel(
        key = preselectedSpeciesId ?: "register:${initialQuery.orEmpty()}",
        factory = RegisterViewModel.factory(container, preselectedSpeciesId, initialQuery),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // ARCHITECTURE.md 4.1 step 1. The grant is taken on the way *in*, before anything else
    // touches the URI: without it the reference dies with the process and the failure is
    // invisible until the next reboot.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val gateway = container.photoGateway
        gateway.persistGrant(uri.toString())
        viewModel.onPhotoPicked(
            PickedPhoto(uri = uri.toString(), displayName = gateway.displayName(uri.toString())),
        )
    }

    // D58. The second way in: the system Files picker (`ACTION_OPEN_DOCUMENT`). Its document
    // URIs come back with the EXIF intact — GPS included — once this app holds
    // `ACCESS_MEDIA_LOCATION`, which the gallery picker's URIs never do (R3). The permission
    // is asked for here and only here, and the picker opens whatever the answer is. Same
    // grant, same PickedPhoto, same flow from here on; only the door differs.
    val files = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val gateway = container.photoGateway
        gateway.persistGrant(uri.toString())
        viewModel.onPhotoPicked(
            PickedPhoto(uri = uri.toString(), displayName = gateway.displayName(uri.toString())),
        )
    }
    val openFiles = { files.launch(arrayOf("image/*")) }
    val mediaLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _: Boolean -> openFiles() }

    // D63. The gallery picker's own stream is redacted, but with the photo-library permission
    // the gateway reads the media-store original behind the picker id, GPS and all. Asked for
    // on the first gallery tap; the picker opens whatever the answer. If the photo on screen
    // was picked before the grant, it is re-read once the grant lands.
    var libraryAccess by remember { mutableStateOf(hasPhotoLibraryAccess(context)) }
    val openPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val libraryThenPick = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _: Map<String, Boolean> ->
        libraryAccess = hasPhotoLibraryAccess(context)
        openPicker()
    }
    val libraryThenReread = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _: Map<String, Boolean> ->
        libraryAccess = hasPhotoLibraryAccess(context)
        if (libraryAccess) state.photo?.let(viewModel::onPhotoPicked)
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is RegisterEvent.Registered -> onRegistered(event.speciesId, event.isFirst)
                RegisterEvent.PhotoUnreadable -> Unit
            }
        }
    }

    RegisterScreen(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onSelectSpecies = viewModel::onSelectSpecies,
        onPickPhoto = {
            if (libraryAccess) openPicker() else libraryThenPick.launch(photoLibraryPermissions())
        },
        onPlaceQueryChange = viewModel::onPlaceQueryChange,
        onPlaceEntered = viewModel::onPlaceEntered,
        onPlacePromptDismissed = viewModel::onPlacePromptDismissed,
        onGrantPhotoAccess = if (libraryAccess) null else {
            { libraryThenReread.launch(photoLibraryPermissions()) }
        },
        onPickFromFiles = {
            val granted = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) openFiles() else mediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        },
        onOpenLens = { uri ->
            try {
                context.startActivity(lensIntentFor(uri))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(lensChooserFor(uri))
            }
        },
        onRegister = viewModel::onRegister,
        onAddSpecies = { onAddSpecies(state.query.trim()) },
    )
}

/**
 * S06's Google Lens hand-off. There is no Lens-specific API worth using here: an ordinary
 * image share is what the user's actual workflow already does, and Lens is one of the targets
 * the chooser offers. `FLAG_GRANT_READ_URI_PERMISSION` is what lets the receiving app open a
 * URI this app only has a read grant on.
 */
internal fun lensChooserFor(photoUri: String): Intent =
    Intent.createChooser(imageShare(photoUri), "Identify this photo")

/**
 * D72. The same share, addressed to Lens's own share target in the Google app, so the 🔍 opens
 * Lens rather than a sheet of contacts. Started directly rather than resolved first — resolving
 * another package needs a `<queries>` entry — so a phone without the Google app throws
 * `ActivityNotFoundException` and the caller falls back to [lensChooserFor].
 */
internal fun lensIntentFor(photoUri: String): Intent =
    imageShare(photoUri).setClassName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.search.lens.LensShareEntryPointActivity",
    )

private fun imageShare(photoUri: String): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "image/*"
    putExtra(Intent.EXTRA_STREAM, Uri.parse(photoUri))
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@Composable
fun RegisterScreen(
    state: RegisterUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectSpecies: (String) -> Unit,
    onPickPhoto: () -> Unit,
    /** D64: the "Where was this?" prompt's two exits. */
    onPlaceQueryChange: (String) -> Unit = {},
    onPlaceEntered: (String) -> Unit = {},
    onPlacePromptDismissed: () -> Unit = {},
    /** D63: null once the photo-library permission is held; otherwise the tap that asks for it. */
    onGrantPhotoAccess: (() -> Unit)? = null,
    onPickFromFiles: () -> Unit = {},
    onOpenLens: (String) -> Unit,
    onRegister: () -> Unit,
    onAddSpecies: () -> Unit = {},
) {
    val colors = DexTheme.colors
    val listState = rememberLazyListState()

    // D18's one-shot. `rememberSaveable` survives a rotation and process death, so the list is
    // never yanked back under a thumb that has already moved; the flag is set *after* the
    // scroll lands, so an early emission with a not-yet-loaded catalogue does not consume it.
    var scrolledToPreselection by rememberSaveable { mutableStateOf(false) }
    val preselectedIndex = state.preselectedIndex
    LaunchedEffect(preselectedIndex, scrolledToPreselection) {
        if (scrolledToPreselection || preselectedIndex == null) return@LaunchedEffect
        // The effect can run before the list has been laid out, and the offset is in pixels.
        val viewport = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        // A negative offset leaves the row a third of the way down rather than jammed against
        // the top edge, so the rows above it show it is a list position, not the list's start.
        listState.scrollToItem(preselectedIndex, -viewport / 3)
        scrolledToPreselection = true
    }

    if (state.placePrompt != null) {
        PlacePromptDialog(
            place = state.place,
            onQueryChange = onPlaceQueryChange,
            onPlaceEntered = onPlaceEntered,
            onDismiss = onPlacePromptDismissed,
        )
    }

    Scaffold(
        containerColor = colors.bg,
        // The bars carry their own insets: Scaffold pads only its content slot, and on an
        // edge-to-edge window that would put the title under the status bar and the ghost
        // button under the gesture bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 10.dp),
                ) {
                    Text(
                        text = "←",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.muted,
                        modifier = Modifier.clickable(onClick = onBack),
                    )
                    Text(
                        text = "Register a Species",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.fg,
                    )
                }
                SearchField(query = state.query, onQueryChange = onQueryChange)
            }
        },
        bottomBar = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp)
                    .padding(top = 10.dp, bottom = 12.dp),
            ) {
                Text(
                    text = "PHOTO · GALLERY OR FILES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    ),
                    color = colors.faint,
                )
                PhotoAttachRow(
                    photo = state.photo,
                    onPickPhoto = onPickPhoto,
                    onPickFromFiles = onPickFromFiles,
                    onOpenLens = onOpenLens,
                )
                // D64/D72. Nothing about the place lives on this screen: the photo answers for
                // itself, and a photo that cannot is asked about when Register is tapped. The
                // one exception is the offer to grant photo access, shown only when a picked
                // photo came back placeless without it (D63).
                if (state.needsPlacePrompt && onGrantPhotoAccess != null) {
                    Text(
                        text = "Allow photo access to read the place from your photos →",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clickable(onClick = onGrantPhotoAccess),
                    )
                }

                state.grantWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.warn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.warnSoft)
                            .padding(10.dp),
                    )
                }

                state.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.stop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.stopSoft)
                            .padding(10.dp),
                    )
                }

                PrimaryCta(
                    label = if (state.registering) "Registering…" else state.registerLabel,
                    enabled = state.canRegister,
                    onClick = onRegister,
                )
            }
        },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // D69. Adding a species is its own action now, with no photo — so a name the dex
            // lacks gets one line that leads there, and only when nothing matches.
            if (state.noResults) {
                item(key = "no-results") {
                    Text(
                        text = "Not in your dex. Add “${state.query.trim()}” ›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onAddSpecies)
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
            items(state.results, key = { it.id }) { species ->
                SpeciesResultRow(
                    species = species,
                    selected = species.id == state.selected?.id,
                    onClick = { onSelectSpecies(species.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = "🔍", style = MaterialTheme.typography.bodyMedium)
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search by name",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.faint,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fg),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * D64/D68. Raised by the Register (or add-your-own) tap when the photo carries no coordinates,
 * and only then. Material's dialog, as in Settings (D49), with a suggestion list under the
 * field: the places this collection already uses, then the region's bundled gazetteer. The
 * list is autocomplete rather than a gate — tapping a row fills the field with that place's
 * own spelling, and anything else typed is taken as written.
 */
@Composable
private fun PlacePromptDialog(
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
                    text = "Register",
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

@Composable
private fun SpeciesResultRow(
    species: SpeciesSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentSoft else colors.card)
            .border(
                1.dp,
                if (selected) colors.accent else colors.rule,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.silBg),
            contentAlignment = Alignment.Center,
        ) {
            SilhouetteIcon(
                silhouetteRes = species.silhouetteRes,
                taxClass = species.taxClass,
                size = 26.dp,
                tint = if (species.caught) colors.accent else colors.sil,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = species.commonName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            species.scientificName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = when {
                selected -> "✓ selected"
                species.caught -> "caught"
                else -> "uncaught"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (selected) colors.accent else colors.faint,
        )
    }
}

/**
 * The picked photo, rendered straight from its content URI — the picker's grant is live at
 * this point and no capture exists yet, so there is no thumbnail to fall back on. This is the
 * one screen that renders a gallery URI before registration.
 */
@Composable
private fun PhotoAttachRow(
    photo: PickedPhoto?,
    onPickPhoto: () -> Unit,
    onPickFromFiles: () -> Unit,
    onOpenLens: (String) -> Unit,
) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
            .clickable(onClick = onPickPhoto)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.silBg),
            contentAlignment = Alignment.Center,
        ) {
            if (photo == null) {
                Text(text = "＋", style = MaterialTheme.typography.titleLarge, color = colors.faint)
            } else {
                AsyncImage(
                    model = photo.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = photo?.displayName ?: "Attach a photo from your gallery",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (photo == null) {
                    // The system picker needs an explicit Done tap after a photo is
                    // highlighted, which is Android's behaviour and not obvious the first time.
                    // D63: with photo access allowed, the gallery's photos keep their place too.
                    "Pick one and tap Done — the place comes with it. 📁 browses this phone's files."
                } else {
                    "Change photo"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (photo == null) colors.faint else colors.accent,
            )
        }
        // D58. Its own tap target rather than a second row: the Files picker is the other way
        // to get the same one photo, for a photo whose place should come along with it.
        Text(
            text = "📁",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accentSoft)
                .clickable(onClick = onPickFromFiles)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        // S06/D72. Lens is the one "what is this?" tool the app offers (S12), so it sits
        // beside the photo it would open, and only once there is one.
        if (photo != null) {
            Text(
                text = "🔍",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.accentSoft)
                    .clickable { onOpenLens(photo.uri) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun PrimaryCta(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) colors.accent else colors.rule)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = if (enabled) colors.card else colors.faint,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews. Nothing renders them without Android Studio (risk R6), but they compile
// and they are the cheapest description of each state the screen has.
// ---------------------------------------------------------------------------

private fun previewSpecies(
    id: String,
    number: Int,
    name: String,
    scientific: String,
    taxClass: TaxClass,
    silhouette: String,
) = SpeciesSummary(
    id = id,
    regionId = "pacific",
    dexNumber = number,
    source = SpeciesSource.CURATED,
    detailsPending = false,
    commonName = name,
    scientificName = scientific,
    taxClass = taxClass,
    kingdom = taxClass.kingdom,
    silhouetteRes = silhouette,
    ecosystemIds = listOf("oak-chaparral"),
    caughtAt = null,
    thumbPath = null,
    captureCount = 0,
)

private val previewResults = listOf(
    previewSpecies(
        "western-screech-owl", 21, "Western Screech-Owl",
        "Megascops kennicottii", TaxClass.BIRD, "sil_bird",
    ),
    previewSpecies(
        "western-fence-lizard", 62, "Western Fence Lizard",
        "Sceloporus occidentalis", TaxClass.REPTILE, "sil_reptile",
    ),
    previewSpecies(
        "western-tanager", 34, "Western Tanager",
        "Piranga ludoviciana", TaxClass.BIRD, "sil_bird",
    ),
)

@Preview(name = "Register — species picked, photo attached", widthDp = 380, heightDp = 800)
@Composable
private fun RegisterReadyPreview() {
    BioDexTheme {
        RegisterScreen(
            state = RegisterUiState(
                query = "western",
                results = previewResults,
                selected = previewResults.first(),
                photo = PickedPhoto(
                    uri = "content://media/external/images/1",
                    displayName = "IMG_20260830_1942.jpg",
                ),
            ),
            onBack = {},
            onQueryChange = {},
            onSelectSpecies = {},
            onPickPhoto = {},
            onOpenLens = {},
            onRegister = {},
        )
    }
}

@Preview(name = "Register — name not in the catalogue", widthDp = 380, heightDp = 800)
@Composable
private fun RegisterNoResultsPreview() {
    BioDexTheme {
        RegisterScreen(
            state = RegisterUiState(query = "varied thrush", results = emptyList()),
            onBack = {},
            onQueryChange = {},
            onSelectSpecies = {},
            onPickPhoto = {},
            onOpenLens = {},
            onRegister = {},
        )
    }
}
