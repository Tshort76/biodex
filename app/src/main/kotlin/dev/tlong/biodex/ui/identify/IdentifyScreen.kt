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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.ui.capture.PickedPhoto
import dev.tlong.biodex.ui.capture.PlacePromptDialog
import dev.tlong.biodex.ui.capture.openInLens
import dev.tlong.biodex.ui.common.PrimaryCta
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.common.WildToggle
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
    onAdd: (IdentifyEvent.Add) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: IdentifyViewModel = viewModel(
        key = "identify:${photo.uri}",
        factory = IdentifyViewModel.factory(context.appContainer, photo),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val placePrompt by viewModel.placePrompt.collectAsStateWithLifecycle()
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    // Lens hands nothing back; the name returns on the clipboard, and fills the search. Android lets only the app
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
                is IdentifyEvent.Add -> onAdd(event)
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
        onOpenLens = {
            viewModel.onLensOpened()
            openInLens(context, photo.uri)
        },
        onQueryChange = viewModel::onQueryChange,
        onSearchOnline = viewModel::onSearchOnline,
        onSelect = viewModel::onSelect,
        onWildChange = viewModel::onWildChange,
        onRegister = viewModel::onRegister,
    )
}

/**
 * D81's layout: the photo takes the top of the screen, the name goes under it, and one button
 * registers — a capture of the species the dex holds, or an add of one it does not.
 */
@Composable
fun IdentifyScreen(
    state: IdentifyUiState,
    message: String?,
    onBack: () -> Unit,
    onOpenLens: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchOnline: () -> Unit,
    onSelect: (String) -> Unit,
    onWildChange: (Boolean) -> Unit,
    onRegister: () -> Unit,
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

        // The photo gives way to everything else, so the keyboard shrinks it rather than the list.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.silBg),
        ) {
            AsyncImage(
                model = state.photo.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = "🔍  Google Lens",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.card)
                    .clickable(onClick = onOpenLens)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }

        NameField(
            query = state.query,
            onQueryChange = onQueryChange,
            canSearchOnline = state.canSearchOnline,
            onSearchOnline = onSearchOnline,
        )
        if (state.query.isEmpty()) {
            Text(
                text = "Copy the name in Lens and come back — it fills in here.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
            )
        }

        onlineLine(state)?.let { OnlineRow(it) }

        if (state.results.isNotEmpty()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(max = 168.dp),
            ) {
                items(state.results, key = { it.id }) { species ->
                    SpeciesRow(species, selected = species.id == state.target?.id, onClick = { onSelect(species.id) })
                }
            }
        }

        WildToggle(wild = state.wild, onWildChange = onWildChange)

        message?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = colors.warn)
        }
        PrimaryCta(
            label = state.registerLabel,
            enabled = state.canRegister,
            onClick = onRegister,
        )
    }
}

@Composable
private fun NameField(
    query: String,
    onQueryChange: (String) -> Unit,
    canSearchOnline: Boolean,
    onSearchOnline: () -> Unit,
) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
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
                // D90: the keyboard's search key asks online, as the button does.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (canSearchOnline) onSearchOnline() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (canSearchOnline) {
            Text(
                text = "Search online",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.card)
                    .clickable(onClick = onSearchOnline)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

/** D88: what the online lookup found, with its picture so it can be held up against the photo. */
@Composable
private fun OnlineRow(line: OnlineLine) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        line.imageUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                // D30: a Wikimedia picture is fitted, never cropped to fill.
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.silBg),
            )
        }
        Text(
            text = line.text,
            style = MaterialTheme.typography.labelSmall,
            color = if (line.warning) colors.warn else colors.muted,
            modifier = Modifier.weight(1f),
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
