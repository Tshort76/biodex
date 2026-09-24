package dev.tlong.biodex.ui.identify

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.domain.PlaceAnswer
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.ui.capture.PickedPhoto
import dev.tlong.biodex.ui.capture.PlacePromptDialog
import dev.tlong.biodex.ui.capture.openInLens
import dev.tlong.biodex.ui.common.PrimaryCta
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * D78. Reached from the grid's ＋ with a photo already picked: find out what it is, then
 * capture it — or add it and capture it — without picking the photo again.
 */
@Composable
fun IdentifyRoute(
    photo: PickedPhoto,
    onBack: () -> Unit,
    onCaptured: (speciesId: String, isFirst: Boolean) -> Unit,
    onAdd: (name: String, photoUri: String, place: PlaceAnswer?) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: IdentifyViewModel = viewModel(
        key = "identify:${photo.uri}",
        factory = IdentifyViewModel.factory(context.appContainer, photo),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val placePrompt by viewModel.placePrompt.collectAsStateWithLifecycle()
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    // Lens hands nothing back; the name returns on the clipboard. Android lets only the app
    // with window focus read it, and focus arrives a moment after resume, so this keys on focus.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        viewModel.onClipboard(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString())
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is IdentifyEvent.Captured -> onCaptured(event.speciesId, event.isFirst)
                is IdentifyEvent.Add -> onAdd(event.name, event.photoUri, event.place)
                IdentifyEvent.Unreadable -> message = "That photo could not be read — nothing was saved."
            }
        }
    }

    val leave = {
        viewModel.onLeave()
        onBack()
    }
    BackHandler(onBack = leave)

    placePrompt?.let { place ->
        PlacePromptDialog(
            place = place,
            onQueryChange = viewModel::onPlaceQueryChange,
            onPlaceEntered = viewModel::onPlaceEntered,
            onDismiss = viewModel::onPlacePromptDismissed,
        )
    }

    IdentifyScreen(
        state = state,
        message = message,
        onBack = leave,
        onOpenLens = { openInLens(context, photo.uri) },
        onQueryChange = viewModel::onQueryChange,
        onUseClipboard = viewModel::onUseClipboard,
        onSelect = viewModel::onSelect,
        onAdd = viewModel::onAdd,
        onCapture = viewModel::onCapture,
    )
}

@Composable
fun IdentifyScreen(
    state: IdentifyUiState,
    message: String?,
    onBack: () -> Unit,
    onOpenLens: () -> Unit,
    onQueryChange: (String) -> Unit,
    onUseClipboard: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onCapture: () -> Unit,
) {
    val colors = DexTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Text(
                text = "←",
                style = MaterialTheme.typography.titleLarge,
                color = colors.muted,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Text(
                text = "What is it?",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.fg,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = state.photo.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.silBg),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = "🔍  Identify with Google Lens",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accentSoft)
                        .clickable(onClick = onOpenLens)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                )
                Text(
                    text = "Copy the name in Lens and come back — it is offered here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.faint,
                )
            }
        }

        state.clipboardOffer?.let { offer ->
            Text(
                text = "Use “$offer” ›",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, colors.accent, RoundedCornerShape(20.dp))
                    .clickable(onClick = onUseClipboard)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        NameField(query = state.query, onQueryChange = onQueryChange)

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(state.results, key = { it.id }) { species ->
                SpeciesRow(species, selected = species.id == state.selected?.id, onClick = { onSelect(species.id) })
            }
            state.addableName?.let { name ->
                item {
                    Text(
                        text = "Not in your dex. Add “$name” and capture it ›",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.accentSoft)
                            .clickable(onClick = onAdd)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }

        message?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = colors.warn)
        }
        PrimaryCta(
            label = if (state.capturing) "Capturing…" else state.captureLabel,
            enabled = state.canCapture,
            onClick = onCapture,
        )
    }
}

@Composable
private fun NameField(query: String, onQueryChange: (String) -> Unit) {
    val colors = DexTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBg)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (query.isEmpty()) {
            Text(
                text = "Type or paste its name",
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

@Composable
private fun SpeciesRow(species: SpeciesSummary, selected: Boolean, onClick: () -> Unit) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentSoft else colors.card)
            .border(1.dp, if (selected) colors.accent else colors.rule, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.silBg),
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
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
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
