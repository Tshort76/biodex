package dev.tlong.biodex.ui.photoviewer

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.data.photo.PhotoRef
import dev.tlong.biodex.data.photo.ownedFileModel
import dev.tlong.biodex.domain.Capture
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhotoViewerRoute(
    captureId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: PhotoViewerViewModel = viewModel(
        key = captureId,
        factory = PhotoViewerViewModel.factory(container, captureId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val relinkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? -> uri?.let { viewModel.relink(it.toString()) } }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            if (event is PhotoViewerEvent.Deleted) onBack()
        }
    }

    PhotoViewerScreen(
        state = state,
        filesDir = container.appContext.filesDir.absolutePath,
        onBack = onBack,
        onToggleFavorite = viewModel::toggleFavorite,
        onDelete = viewModel::delete,
        onRelink = {
            relinkPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onRetry = viewModel::resolve,
    )
}

@Composable
fun PhotoViewerScreen(
    state: PhotoViewerUiState,
    filesDir: String,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onRelink: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = DexTheme.colors
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(containerColor = colors.bg) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "← Back",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 10.dp),
            )

            val capture = state.capture
            if (capture == null) {
                Text(
                    text = if (state.loading) "Loading…" else "That photo is no longer in the dex.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.faint,
                )
                return@Column
            }

            PhotoFrame(
                state = state,
                capture = capture,
                filesDir = filesDir,
            )

            val availability = state.availability
            availability.bannerText?.let { message ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.warnSoft)
                        .padding(12.dp),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warn,
                    )
                    if (availability.offerRelink) {
                        ActionRow(label = "Re-link photo", emphasised = true, onClick = onRelink)
                    } else {
                        ActionRow(label = "Try again", emphasised = false, onClick = onRetry)
                    }
                }
            }

            Text(
                text = state.speciesName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.fg,
            )
            Text(
                text = listOfNotNull(
                    photoDateFormat.format(Date(capture.takenAt)),
                    capture.locationLabel,
                    capture.lat?.let { lat ->
                        capture.lng?.let { lng -> "%.4f, %.4f".format(lat, lng) }
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
            capture.note?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.fg)
            }

            ActionRow(
                label = if (state.isFavorite) "★ Favorite photo" else "☆ Make this the favorite",
                emphasised = state.isFavorite,
                onClick = onToggleFavorite,
            )

            if (confirmingDelete) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.stopSoft)
                        .padding(12.dp),
                ) {
                    Text(
                        text = if (state.isLastCapture) {
                            "This is the only photo of ${state.speciesName}. Deleting it " +
                                "reverts the species to uncaught. Your gallery photo is not " +
                                "touched."
                        } else {
                            "Delete this capture? The photo stays in your gallery — only the " +
                                "app's link and thumbnail go."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.stop,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionRow(
                            label = "Delete",
                            emphasised = true,
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                        )
                        ActionRow(
                            label = "Keep",
                            emphasised = false,
                            onClick = { confirmingDelete = false },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                ActionRow(
                    label = "Delete this capture",
                    emphasised = false,
                    onClick = { confirmingDelete = true },
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

/**
 * The full-size photo when the reference resolves, and the stored thumbnail whenever it does
 * not (M12). There is deliberately no third branch: a blank box is the one thing this screen
 * must never show.
 */
@Composable
private fun PhotoFrame(
    state: PhotoViewerUiState,
    capture: Capture,
    filesDir: String,
) {
    val colors = DexTheme.colors
    val thumbModel = ownedFileModel(java.io.File(filesDir), capture.thumbPath)
    val fullModel = when (val ref = state.ref) {
        is PhotoRef.Available -> ref.uri
        is PhotoRef.LocalCopy -> ownedFileModel(java.io.File(filesDir), ref.relativePath)
        else -> null
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.silBg),
    ) {
        AsyncImage(
            model = fullModel ?: thumbModel,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (fullModel == null && state.ref != null) {
            Text(
                text = "stored thumbnail",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.card.copy(alpha = 0.8f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    emphasised: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DexTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (emphasised) colors.accent else colors.rule, RoundedCornerShape(10.dp))
            .background(if (emphasised) colors.accentSoft else colors.card)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasised) colors.accent else colors.muted,
        )
    }
}

private val photoDateFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())

private fun previewCapture() = Capture(
    id = "cap-1",
    speciesId = "western-screech-owl",
    photoUri = "content://media/external/images/1",
    thumbPath = "thumbnails/cap-1.jpg",
    takenAt = 1_788_118_920_000L,
    locationLabel = "Shevlin Park, Bend",
    note = "Calling at dusk from the cavity in the big ponderosa.",
    createdAt = 1_788_118_920_000L,
)

@Preview(name = "Photo viewer — revoked reference", widthDp = 380, heightDp = 800)
@Composable
private fun PhotoViewerRevokedPreview() {
    BioDexTheme {
        PhotoViewerScreen(
            state = PhotoViewerUiState(
                capture = previewCapture(),
                speciesName = "Western Screech-Owl",
                speciesId = "western-screech-owl",
                ref = PhotoRef.Revoked,
                isFavorite = true,
                isLastCapture = true,
                loading = false,
            ),
            filesDir = "/data/user/0/dev.tlong.biodex/files",
            onBack = {},
            onToggleFavorite = {},
            onDelete = {},
            onRelink = {},
            onRetry = {},
        )
    }
}
