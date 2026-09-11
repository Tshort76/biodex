package dev.tlong.biodex.ui.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.data.photo.ownedFileModel
import dev.tlong.biodex.domain.Capture
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesDetail
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.ui.common.AttributionLine
import dev.tlong.biodex.ui.common.CaughtChip
import dev.tlong.biodex.ui.common.DIMMED_ALPHA
import dev.tlong.biodex.ui.common.DIMMED_FILTER
import dev.tlong.biodex.ui.common.DexFilterChip
import dev.tlong.biodex.ui.common.LinkRow
import dev.tlong.biodex.ui.common.RangeMap
import dev.tlong.biodex.ui.common.ScientificName
import dev.tlong.biodex.ui.common.SectionHeader
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.common.TileState
import dev.tlong.biodex.ui.common.UsesSection
import dev.tlong.biodex.ui.common.tileStateFor
import dev.tlong.biodex.ui.reveal.RevealContent
import dev.tlong.biodex.ui.reveal.UnlockRevealOverlay
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Frame 2 of `mockup.html`: the read-only entry detail (M03, and M04 minus the pieces later
 * slices own — the streamed reference image is slice 6's, the photo strip is slice 5's).
 */
@Composable
fun EntryDetailRoute(
    speciesId: String,
    justUnlocked: Boolean,
    photoAdded: Boolean,
    onBack: () -> Unit,
    onRegister: (String) -> Unit,
    onOpenPhoto: (String) -> Unit,
    onOpenNearest: () -> Unit,
    onBackfillReady: (draftId: String) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val viewModel: EntryDetailViewModel = viewModel(
        key = speciesId,
        factory = EntryDetailViewModel.factory(container, speciesId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The reveal is a one-shot moment, not a property of the destination: `rememberSaveable`
    // is what stops a rotation or a process death replaying it (6.1's route argument stays
    // true for the life of the back-stack entry).
    var revealPending by rememberSaveable(speciesId) { mutableStateOf(justUnlocked) }

    // The repeat-registration acknowledgment (M09): a "+1" that shows for a moment and goes.
    // Same one-shot guard, for the same reason.
    // M20's trigger. The ViewModel decides whether a lookup is owed and whether it succeeded;
    // this only routes the result, once, to the confirmation card.
    LaunchedEffect(speciesId) {
        viewModel.backfillEvents.collect { draftId -> onBackfillReady(draftId) }
    }

    var toastPending by rememberSaveable(speciesId) { mutableStateOf(photoAdded) }
    if (toastPending) {
        LaunchedEffect(speciesId) {
            delay(PHOTO_ADDED_TOAST_MS)
            toastPending = false
        }
    }

    Box {
        EntryDetailScreen(
            state = state,
            filesDir = container.appContext.filesDir.absolutePath,
            onBack = onBack,
            onRegister = onRegister,
            onOpenPhoto = onOpenPhoto,
            onOpenNearest = onOpenNearest,
            onPreferOwnPhoto = viewModel::onPreferOwnPhoto,
        )
        val detail = state.detail
        if (revealPending && detail != null) {
            UnlockRevealOverlay(
                content = RevealContent(
                    commonName = detail.summary.commonName,
                    displayNumber = detail.summary.displayNumber,
                    scientificName = detail.summary.scientificName,
                    taxClass = detail.summary.taxClass,
                    kingdom = detail.summary.kingdom,
                    silhouetteRes = detail.summary.silhouetteRes,
                    // M41: a photoless catch reveals into the species' own reference picture
                    // rather than staying a silhouette, and says so with the leaf.
                    thumbnailModel = ownedFileModel(
                        container.appContext.filesDir,
                        state.captures.firstOrNull()?.thumbPath,
                    ) ?: detail.summary.imageUrl,
                    leafMark = tileStateFor(detail.summary) == TileState.CAUGHT_NO_OWN_PHOTO,
                    caughtCount = state.caughtCount,
                    totalCount = state.totalCount,
                    whereAndWhen = state.captures.firstOrNull()?.let {
                        listOfNotNull(
                            formatCaughtDate(it.takenAt),
                            it.locationLabel,
                        ).joinToString(" · ")
                    },
                ),
                onDismiss = { revealPending = false },
            )
        }
        if (toastPending && !revealPending) {
            Text(
                text = "+1 photo",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = DexTheme.colors.accent,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DexTheme.colors.accentSoft)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

/** Long enough to read, short enough not to be in the way (DESIGN.md §4's "brief"). */
private const val PHOTO_ADDED_TOAST_MS = 1_800L

@Composable
fun EntryDetailScreen(
    state: EntryDetailUiState,
    filesDir: String,
    onBack: () -> Unit,
    onRegister: (String) -> Unit,
    onOpenPhoto: (String) -> Unit,
    onOpenNearest: () -> Unit,
    onPreferOwnPhoto: (Boolean) -> Unit = {},
) {
    val colors = DexTheme.colors
    Scaffold(containerColor = colors.bg) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "← Dex",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 10.dp),
            )
            val detail = state.detail
            when {
                state.loading -> Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.faint,
                )

                detail == null -> Text(
                    text = "That species is not in the catalogue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.faint,
                )

                else -> DetailBody(
                    detail = detail,
                    state = state,
                    filesDir = filesDir,
                    onRegister = onRegister,
                    onOpenPhoto = onOpenPhoto,
                    onOpenNearest = onOpenNearest,
                    onPreferOwnPhoto = onPreferOwnPhoto,
                )
            }
        }
    }
}

@Composable
private fun DetailBody(
    detail: SpeciesDetail,
    state: EntryDetailUiState,
    filesDir: String,
    onRegister: (String) -> Unit,
    onOpenPhoto: (String) -> Unit,
    onOpenNearest: () -> Unit,
    onPreferOwnPhoto: (Boolean) -> Unit,
) {
    val colors = DexTheme.colors
    val uriHandler = LocalUriHandler.current
    val summary = detail.summary
    val ecosystemNames = state.ecosystemNames
    val captures = state.captures

    Hero(
        summary = summary,
        imageUrl = detail.imageUrl,
        imageAttribution = detail.imageAttribution,
        online = state.online,
        ownPhotoModel = if (summary.preferOwnPhoto) ownedFileModel(File(filesDir), summary.thumbPath) else null,
    )
    // M46: only when there are two pictures to choose between — a caught species with a
    // thumbnail of its own *and* a reference picture. A photoless plant has nothing to
    // toggle to, and a user-added species with no picture yet has nothing to toggle from.
    if (summary.caught && summary.thumbPath != null && detail.imageUrl != null) {
        PicturePreference(
            preferOwnPhoto = summary.preferOwnPhoto,
            onPreferOwnPhoto = onPreferOwnPhoto,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Text(
            text = summary.commonName,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.fg,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = summary.displayNumber,
            style = MaterialTheme.typography.labelMedium,
            // `.pnum` — the P-number is the kingdom mark (M26), and the mockup gives it the
            // plant colour so a glance at the header says which list this entry is on.
            color = if (summary.kingdom == Kingdom.PLANT) colors.ok else colors.faint,
        )
    }
    // Frame 7's `.sci` reads "Sambucus cerulea · shrub": a plant's growth form is not
    // guessable from its silhouette the way a bird's class is, so the plant detail names it.
    summary.scientificName?.let { name ->
        ScientificName(
            if (summary.kingdom == Kingdom.PLANT) "$name · ${summary.taxClass.wireName}" else name,
        )
    }

    if (ecosystemNames.isNotEmpty()) {
        Text(
            text = ecosystemNames.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.warn,
            modifier = Modifier.padding(top = 3.dp),
        )
    }

    if (summary.caught) {
        CaughtChip(
            dateLabel = formatCaughtDate(summary.caughtAt),
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    // M20. Reached only when the lookup could not run — online, the ViewModel has already
    // sent the user to the confirmation card by the time this frame is composed.
    if (summary.detailsPending) {
        Text(
            text = if (state.online) {
                "Details pending — the lookup found nothing for this name yet. It will try " +
                    "again the next time you open this entry."
            } else {
                "Details pending — connect to the internet and open this entry to fill in " +
                    "its name, habitat and picture."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.warn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.warnSoft)
                .padding(10.dp),
        )
    }

    // D34's map sits under the Range header and above Habitat: it is the same question the
    // habitat paragraph answers, and a picture of it reads faster than the paragraph does.
    // Absent entirely when there is nothing to draw, header and all.
    state.rangeMap?.let {
        SectionHeader("Range")
        RangeMap(grid = it.grid, cells = it.cells, modifier = Modifier.padding(top = 2.dp))
    }

    // D36. A link rather than the lineage chain the second design pass proposed: the owner
    // cut the chain, and the useful thing on this screen is the way out to the comparison,
    // not the path itself. Hidden for a species with no lineage — every user-added one until
    // its backfill — because the screen behind it would have nothing to show.
    if (detail.summary.lineage.isKnown) {
        NearestLink(onClick = onOpenNearest)
    }

    SectionHeader("Habitat")
    Text(
        text = detail.habitatText ?: detail.description ?: "No habitat text bundled.",
        style = MaterialTheme.typography.bodySmall,
        color = if (detail.habitatText == null && detail.description == null) {
            colors.faint
        } else {
            colors.fg
        },
    )

    // A plant's uses stand between Habitat and the photo strip (M24, D15); an animal, and a
    // plant with nothing documented, gets nothing here and goes straight to the photo strip.
    state.uses?.let { UsesSection(content = it, modifier = Modifier.padding(top = 2.dp)) }

    if (captures.isNotEmpty()) {
        SectionHeader(
            if (captures.all { it.thumbPath == null }) {
                // M41: nothing here is linked from a gallery, so the old header would be a
                // lie about where these catches came from.
                "My catches (${captures.size}) · no photos kept"
            } else {
                "My photos (${captures.size}) · linked from gallery"
            },
        )
        PhotoStrip(
            captures = captures,
            filesDir = filesDir,
            onOpenPhoto = onOpenPhoto,
            onAddPhoto = { onRegister(summary.id) },
        )
    }

    if (!summary.caught) {
        Button(
            onClick = { onRegister(summary.id) },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.card,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        ) {
            Text(
                text = "Register this species",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }

    val infoUrl = detail.infoUrl
    LinkRow(
        label = if (infoUrl != null) "Learn more" else "No reference link",
        enabled = infoUrl != null,
        onClick = { infoUrl?.let(uriHandler::openUri) },
        modifier = Modifier.padding(top = 10.dp),
    )

    detail.imageAttribution?.let {
        AttributionLine(text = it, modifier = Modifier.padding(top = 8.dp))
    }
    AttributionLine(
        text = "Your photos stay in your gallery and are linked by reference — the app keeps " +
            "only a small thumbnail. The reference image streams from its source and is " +
            "cached after first view.",
        modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
    )
}

/**
 * `.strip` — the user's own photos, newest first (M04), plus the mockup's `＋` tile for a
 * repeat registration.
 *
 * Every tile renders the **stored thumbnail only** (M11). The gallery URI is never resolved
 * here: that is what guarantees a broken reference can dim one photo in the Photo Viewer but
 * can never blank the collection.
 */
/**
 * The way out to D36's Nearest screen. A row rather than a button because it is a detour
 * from this species, not an action on it.
 */
@Composable
private fun NearestLink(onClick: () -> Unit) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(
            text = "Nearest species",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.accent,
        )
        Box(modifier = Modifier.weight(1f))
        Text(text = "\u203A", color = colors.faint)
    }
}

@Composable
private fun PhotoStrip(
    captures: List<Capture>,
    filesDir: String,
    onOpenPhoto: (String) -> Unit,
    onAddPhoto: () -> Unit,
) {
    val colors = DexTheme.colors
    val dir = File(filesDir)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
    ) {
        captures.forEach { capture ->
            // M41. A capture with no photograph is a date-and-place row and nothing more: no
            // thumbnail, and **no tap target**, because the viewer it would open exists to
            // show a photo and to offer a re-link, and neither means anything here. This is
            // where that capture is kept out of the viewer — the route never learns about it.
            val hasPhoto = capture.thumbPath != null
            Column(
                modifier = Modifier
                    .width(92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (hasPhoto) colors.silBg else colors.accentSoft)
                    .then(
                        if (hasPhoto) {
                            Modifier.clickable { onOpenPhoto(capture.id) }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (hasPhoto) {
                    AsyncImage(
                        model = ownedFileModel(dir, capture.thumbPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) {
                        Text(
                            text = "🍃",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                Text(
                    text = formatCaughtDate(capture.takenAt),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = colors.muted,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(92.dp)
                .height(92.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.codeBg)
                .clickable(onClick = onAddPhoto),
        ) {
            Text(text = "＋", style = MaterialTheme.typography.headlineSmall, color = colors.faint)
        }
    }
}

/**
 * `.hero` — the frame the whole slice is for. What goes in it is decided by [heroVisual]:
 * a caught species streams its Wikimedia image through Coil (disk-cached, so S02's "works
 * offline the second time" is real), and every other case falls back to the class silhouette
 * D3 asks for rather than to a hole.
 *
 * The silhouette is drawn **underneath** the image rather than as Coil's error slot, so the
 * frame is never empty for the moment between request and first pixel.
 */
@Composable
private fun PicturePreference(
    preferOwnPhoto: Boolean,
    onPreferOwnPhoto: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Text(
            text = "Show",
            style = MaterialTheme.typography.labelSmall,
            color = DexTheme.colors.faint,
        )
        DexFilterChip(
            label = "Stock photo",
            selected = !preferOwnPhoto,
            onClick = { onPreferOwnPhoto(false) },
        )
        DexFilterChip(
            label = "My photo",
            selected = preferOwnPhoto,
            onClick = { onPreferOwnPhoto(true) },
        )
    }
}

@Composable
private fun Hero(
    summary: SpeciesSummary,
    imageUrl: String?,
    imageAttribution: String?,
    online: Boolean,
    ownPhotoModel: String? = null,
) {
    val colors = DexTheme.colors
    // M46: the own thumbnail is a local file, so it loads or it does not; once it has not,
    // the hero forgets the preference for this composition and shows the reference picture.
    var ownFailed by remember(ownPhotoModel) { mutableStateOf(false) }
    val ownModel = ownPhotoModel?.takeUnless { ownFailed }
    // Retrying a failed load means building a *new* Coil painter — resetting our own phase
    // restarts nothing, because the model has not changed and `onState` never fires again.
    // Hence the generation counter: it keys the AsyncImage, so coming back online after a
    // failure re-requests the image without the user leaving and re-entering the screen.
    // Connectivity deliberately does not key `phase`: an image already on screen must not be
    // demoted to "loading" (and hidden) just because the phone went into airplane mode.
    var generation by remember(imageUrl) { mutableIntStateOf(0) }
    var phase by remember(imageUrl, generation) { mutableStateOf(ImageLoadPhase.LOADING) }
    LaunchedEffect(online, phase) {
        if (online && phase == ImageLoadPhase.FAILED) generation++
    }
    val visual = heroVisual(
        imageUrl = imageUrl,
        caught = summary.caught,
        phase = phase,
        online = online,
        ownPhotoModel = ownModel,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.silBg),
        contentAlignment = Alignment.Center,
    ) {
        // Hidden once the photograph is actually on screen. The image is scaled to fit rather
        // than to fill (D30), so it letterboxes, and a silhouette showing through the bands
        // reads as a rendering fault rather than as a placeholder. Every other phase still
        // draws it, which is what keeps the frame from being empty between request and pixel.
        if (visual !is HeroVisual.Reference &&
            visual !is HeroVisual.DimmedReference &&
            visual !is HeroVisual.OwnPhoto
        ) {
            SilhouetteIcon(
                silhouetteRes = summary.silhouetteRes,
                taxClass = summary.taxClass,
                size = 120.dp,
                tint = if (summary.caught) colors.accent else colors.sil,
                // D53. An uncaught frame never draws anything at full strength: the
                // silhouette is the placeholder for a picture that arrives dimmed, and at
                // full tint it lands as a hard dark shape that then fades to pale grey.
                modifier = Modifier.alpha(if (summary.caught) 1f else DIMMED_ALPHA),
            )
        }
        // Requested whenever there is something to request, and hidden rather than removed
        // when it is not the thing on show. Taking a failed image out of the composition would
        // reset Coil's painter, which reports its way back to Loading — and the hero would
        // then retry forever against a URL that is not answering.
        if (visual is HeroVisual.OwnPhoto) {
            key(visual.model) {
                AsyncImage(
                    model = visual.model,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onState = { coilState ->
                        if (coilState is AsyncImagePainter.State.Error) ownFailed = true
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // D52: an uncaught species requests the same picture a caught one does. It used to be
        // skipped entirely, which is what left the silhouette on screen here while the grid
        // tile beside it already drew the species.
        if (imageUrl != null && ownModel == null) {
            val dimmed = visual is HeroVisual.DimmedReference
            key(imageUrl, generation) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    // D30. Reference photographs arrive at whatever aspect ratio Wikimedia
                    // holds them at, and filling a 158dp letterbox frame with a portrait bird
                    // shot cropped the bird out of it. Fit shows the whole animal.
                    contentScale = ContentScale.Fit,
                    colorFilter = if (dimmed) DIMMED_FILTER else null,
                    alpha = when {
                        dimmed -> DIMMED_ALPHA
                        visual is HeroVisual.Reference -> 1f
                        else -> 0f
                    },
                    onState = { coilState ->
                        when (coilState) {
                            is AsyncImagePainter.State.Success -> phase = ImageLoadPhase.LOADED
                            is AsyncImagePainter.State.Error -> phase = ImageLoadPhase.FAILED
                            is AsyncImagePainter.State.Loading -> phase = ImageLoadPhase.LOADING
                            // Empty is the painter's disposed/reset state, not an outcome.
                            is AsyncImagePainter.State.Empty -> Unit
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // M17: the credit belongs on the photograph, not on a silhouette we drew ourselves.
        if (visual is HeroVisual.Reference && imageAttribution != null) {
            Text(
                text = "Reference photo · $imageAttribution",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.muted,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.card.copy(alpha = 0.75f))
                    .padding(horizontal = 4.dp),
            )
        }
    }
    heroNote(visual)?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = colors.faint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val caughtDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

internal fun formatCaughtDate(caughtAt: Long?): String =
    caughtAt?.let { caughtDateFormat.format(Date(it)) } ?: ""

// ---------------------------------------------------------------------------
// Previews (see the note in DexGridScreen.kt).
// ---------------------------------------------------------------------------

private fun previewDetail(caught: Boolean) = SpeciesDetail(
    summary = SpeciesSummary(
        id = "western-screech-owl",
        regionId = "pacific",
        dexNumber = 21,
        source = SpeciesSource.CURATED,
        detailsPending = false,
        commonName = "Western Screech-Owl",
        scientificName = "Megascops kennicottii",
        taxClass = TaxClass.BIRD,
        kingdom = Kingdom.ANIMAL,
        silhouetteRes = "sil_bird",
        ecosystemIds = listOf("oak-chaparral", "riparian-wetland", "urban-suburban"),
        caughtAt = if (caught) 1_756_512_000_000L else null,
        thumbPath = null,
        captureCount = if (caught) 2 else 0,
    ),
    habitatText = "Low-elevation woodlands, streamside groves and suburban parks; roosts by " +
        "day in tree cavities. Listen for a soft bouncing-ball trill at dusk.",
    description = null,
    imageUrl = "https://upload.wikimedia.org/example.jpg",
    infoUrl = "https://en.wikipedia.org/wiki/Western_screech_owl",
    imageAttribution = "Wikimedia · CC BY-SA",
    userEditedFields = emptyList(),
)

@Preview(name = "Entry detail — caught, with photo strip", widthDp = 380, heightDp = 900)
@Composable
private fun EntryDetailCaughtPreview() {
    BioDexTheme {
        EntryDetailScreen(
            state = EntryDetailUiState(
                detail = previewDetail(caught = true),
                ecosystemNames = listOf("Oak Woodland & Chaparral", "Riparian & Wetland"),
                captures = listOf(
                    Capture(
                        id = "cap-1",
                        speciesId = "western-screech-owl",
                        photoUri = "content://media/external/images/1",
                        thumbPath = "thumbnails/cap-1.jpg",
                        takenAt = 1_788_118_920_000L,
                        createdAt = 1_788_118_920_000L,
                    ),
                ),
                caughtCount = 47,
                totalCount = 120,
                loading = false,
            ),
            filesDir = "/data/user/0/dev.tlong.biodex/files",
            onBack = {},
            onRegister = {},
            onOpenPhoto = {},
            onOpenNearest = {},
        )
    }
}

@Preview(name = "Entry detail — uncaught", widthDp = 380, heightDp = 780)
@Composable
private fun EntryDetailUncaughtPreview() {
    BioDexTheme {
        EntryDetailScreen(
            state = EntryDetailUiState(
                detail = previewDetail(caught = false),
                ecosystemNames = listOf("Oak Woodland & Chaparral"),
                caughtCount = 46,
                totalCount = 120,
                loading = false,
            ),
            filesDir = "/data/user/0/dev.tlong.biodex/files",
            onBack = {},
            onRegister = {},
            onOpenPhoto = {},
            onOpenNearest = {},
        )
    }
}
