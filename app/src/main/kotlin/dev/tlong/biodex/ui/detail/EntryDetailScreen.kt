package dev.tlong.biodex.ui.detail

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
import dev.tlong.biodex.ui.common.LinkRow
import dev.tlong.biodex.ui.common.ScientificName
import dev.tlong.biodex.ui.common.SectionHeader
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.common.UsesSection
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
                    thumbnailModel = ownedFileModel(
                        container.appContext.filesDir,
                        state.captures.firstOrNull()?.thumbPath,
                    ),
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
    )

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
        SectionHeader("My photos (${captures.size}) · linked from gallery")
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
            Column(
                modifier = Modifier
                    .width(92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.silBg)
                    .clickable { onOpenPhoto(capture.id) },
            ) {
                AsyncImage(
                    model = ownedFileModel(dir, capture.thumbPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                )
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
private fun Hero(
    summary: SpeciesSummary,
    imageUrl: String?,
    imageAttribution: String?,
    online: Boolean,
) {
    val colors = DexTheme.colors
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
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.silBg),
        contentAlignment = Alignment.Center,
    ) {
        SilhouetteIcon(
            silhouetteRes = summary.silhouetteRes,
            taxClass = summary.taxClass,
            size = 120.dp,
            tint = if (summary.caught) colors.accent else colors.sil,
        )
        // Requested whenever there is something to request, and hidden rather than removed
        // when it is not the thing on show. Taking a failed image out of the composition would
        // reset Coil's painter, which reports its way back to Loading — and the hero would
        // then retry forever against a URL that is not answering.
        if (summary.caught && imageUrl != null) {
            key(imageUrl, generation) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = if (visual is HeroVisual.Reference) 1f else 0f,
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
        )
    }
}
