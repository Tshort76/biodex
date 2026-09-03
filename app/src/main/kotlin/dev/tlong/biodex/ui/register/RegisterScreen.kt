package dev.tlong.biodex.ui.register

import android.content.Intent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.tlong.biodex.data.identify.ResolvedCandidate
import dev.tlong.biodex.data.net.LookupOutcome
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
 * attach one gallery photo through the system picker, register. There is no camera anywhere
 * in this app, by design (DESIGN.md §7).
 */
@Composable
fun RegisterRoute(
    preselectedSpeciesId: String?,
    onBack: () -> Unit,
    onRegistered: (speciesId: String, justUnlocked: Boolean) -> Unit,
    onAddOwnSpecies: (
        typedName: String,
        photoUri: String,
        prefetched: LookupOutcome?,
    ) -> Unit,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: RegisterViewModel = viewModel(
        key = preselectedSpeciesId ?: "register",
        factory = RegisterViewModel.factory(container, preselectedSpeciesId),
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

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is RegisterEvent.Registered -> onRegistered(event.speciesId, event.isFirst)
                is RegisterEvent.AddOwnSpecies ->
                    onAddOwnSpecies(event.typedName, event.photoUri, event.prefetched)

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
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onOpenLens = { uri -> context.startActivity(lensChooserFor(uri)) },
        onRegister = viewModel::onRegister,
        onIdentify = viewModel::onIdentify,
        onPickCandidate = viewModel::onPickCandidate,
        onDismissCandidates = viewModel::onDismissIdentification,
        onAddOwnSpecies = { name, uri -> onAddOwnSpecies(name, uri, null) },
    )
}

/**
 * S06's Google Lens hand-off. There is no Lens-specific API worth using here: an ordinary
 * image share is what the user's actual workflow already does, and Lens is one of the targets
 * the chooser offers. `FLAG_GRANT_READ_URI_PERMISSION` is what lets the receiving app open a
 * URI this app only has a read grant on.
 */
internal fun lensChooserFor(photoUri: String): Intent {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(photoUri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(share, "Identify this photo")
}

@Composable
fun RegisterScreen(
    state: RegisterUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectSpecies: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onOpenLens: (String) -> Unit,
    onRegister: () -> Unit,
    onIdentify: () -> Unit = {},
    onPickCandidate: (ResolvedCandidate) -> Unit = {},
    onDismissCandidates: () -> Unit = {},
    onAddOwnSpecies: (typedName: String, photoUri: String) -> Unit,
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
                    text = "PHOTO · FROM YOUR GALLERY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    ),
                    color = colors.faint,
                )
                PhotoAttachRow(photo = state.photo, onPickPhoto = onPickPhoto)

                // M31/M38. Hidden entirely for a kingdom with no provider; present but
                // disabled with the reason inline when something the user can act on is in
                // the way. S06's Lens share stays below it either way — it is still the right
                // tool when the service has nothing (S12).
                if (state.identifyVisible) {
                    IdentifyButton(
                        label = state.identifyLabel,
                        disabledReason = state.identifyDisabledReason,
                        onClick = onIdentify,
                    )
                }

                state.photo?.let { picked ->
                    Text(
                        text = "Not sure what it is? Open photo in Google Lens ↗",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.accentSoft)
                            .clickable { onOpenLens(picked.uri) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
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

                // M08. The flow needs both halves of what only the user has — the name and the
                // photo (M20 creates an offline entry "from the name and photo alone"), so the
                // button waits for the photo rather than opening a card that cannot be saved.
                GhostCta(
                    label = state.addOwnLabel,
                    enabled = state.canAddOwn,
                    onClick = {
                        val photo = state.photo ?: return@GhostCta
                        onAddOwnSpecies(state.query.trim(), photo.uri)
                    },
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
            // §5.2 rule 1: the panel sits above the catalogue list, inside the scrolling
            // region, so the pinned search, photo row and buttons of D18 are untouched.
            candidatePanel(
                identification = state.identification,
                selectedSpeciesId = state.selected?.id,
                onPickCandidate = onPickCandidate,
                onDismiss = onDismissCandidates,
            )

            if (state.noResults) {
                item(key = "no-results") {
                    Text(
                        text = "No catalogue species matches “${state.query}”. If you " +
                            "photographed something outside the Pacific catalogue, add it as " +
                            "your own species.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.faint,
                        modifier = Modifier.padding(vertical = 6.dp),
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
private fun PhotoAttachRow(photo: PickedPhoto?, onPickPhoto: () -> Unit) {
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
                    "Pick one and tap Done. Linked, never copied — only a thumbnail is kept."
                } else {
                    "Change photo"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (photo == null) colors.faint else colors.accent,
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

@Composable
private fun GhostCta(
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
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.faint,
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
            onAddOwnSpecies = { _, _ -> },
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
            onAddOwnSpecies = { _, _ -> },
        )
    }
}
