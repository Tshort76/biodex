package dev.tlong.biodex.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.data.identify.PlantNetIdentifier
import dev.tlong.biodex.data.photo.GrantPressure
import dev.tlong.biodex.ui.common.SectionHeader
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * Settings (S03, S01, cache management, licenses). Reached from the grid's top-bar gear —
 * the mockup has no settings frame, so this screen follows the mockup's *language* (section
 * headers, cards, the accent CTA) rather than a picture of itself.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.appContainer
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // S01's other half. `OpenDocument` rather than `GetContent`: the user is picking a file
    // they already have, and this is the picker that shows Downloads and Drive.
    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.import(uri.toString())
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShareArchive ->
                    context.startActivity(archiveChooserFor(event.uri, event.fileName))
            }
        }
    }

    SettingsScreen(
        state = state,
        onBack = onBack,
        onKeepLocalCopy = viewModel::setKeepLocalCopy,
        onExport = viewModel::export,
        onImport = { archivePicker.launch(ARCHIVE_MIME_TYPES) },
        onClearCaches = viewModel::clearReferenceCaches,
        onOpenLicenses = onOpenLicenses,
        onPlantNetKey = viewModel::setPlantNetKey,
        onOpenUrl = { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        },
    )
}

/**
 * A ZIP is often typed `application/octet-stream` by whichever app wrote it, and Google
 * Drive types its own downloads differently again, so the picker accepts both rather than
 * greying out the file the user came here for.
 */
private val ARCHIVE_MIME_TYPES = arrayOf("application/zip", "application/octet-stream")

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onKeepLocalCopy: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearCaches: () -> Unit,
    onOpenLicenses: () -> Unit,
    onPlantNetKey: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    val colors = DexTheme.colors
    Scaffold(containerColor = colors.bg) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.muted,
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.fg,
                )
            }

            if (state.message != null) {
                MessageCard(text = state.message, warning = state.messageIsWarning)
            }

            SectionHeader("Photos")
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep a local copy",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = colors.fg,
                        )
                        Text(
                            text = "Off by default: the dex links to your gallery rather than " +
                                "duplicating it. Turn this on and every new registration also " +
                                "stores a full-size copy inside the app, so the photo survives " +
                                "being deleted from the gallery. It does not copy captures you " +
                                "already have.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                        )
                    }
                    Switch(
                        checked = state.keepLocalCopy,
                        onCheckedChange = onKeepLocalCopy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.card,
                            checkedTrackColor = colors.accent,
                        ),
                    )
                }
                Text(
                    text = grantLine(state.grantCount, state.grantPressure),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.grantPressure == GrantPressure.FINE) {
                        colors.faint
                    } else {
                        colors.warn
                    },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            SectionHeader("Backup")
            SettingCard {
                Text(
                    text = "Export writes one ZIP: your collection as JSON, every thumbnail, " +
                        "and a full-size copy of every photo whose gallery reference still " +
                        "works. Photos you have since deleted from the gallery cannot be " +
                        "included — the export tells you exactly how many.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                Box(modifier = Modifier.height(10.dp))
                PrimaryButton(
                    label = when (state.busy) {
                        SettingsBusy.EXPORTING -> "Exporting…"
                        else -> "Export collection…"
                    },
                    enabled = state.busy == null,
                    onClick = onExport,
                )
                Box(modifier = Modifier.height(6.dp))
                SecondaryButton(
                    label = when (state.busy) {
                        SettingsBusy.IMPORTING -> "Importing…"
                        else -> "Import from an archive…"
                    },
                    enabled = state.busy == null,
                    onClick = onImport,
                )
                Text(
                    text = "Import adds what is missing and never overwrites or deletes what " +
                        "is already here. Restored photos are stored inside the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.faint,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionHeader("Caches")
            SettingCard {
                Text(
                    text = cacheLine(state.cacheSizes),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fg,
                )
                Text(
                    text = "Reference images are downloaded once and kept so " +
                        "entries work offline. Clearing them frees space and changes nothing " +
                        "about your collection — your own photos, thumbnails and entries are " +
                        "never touched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Box(modifier = Modifier.height(10.dp))
                SecondaryButton(
                    label = when (state.busy) {
                        SettingsBusy.CLEARING -> "Clearing…"
                        else -> "Clear reference caches"
                    },
                    enabled = state.busy == null,
                    onClick = onClearCaches,
                )
            }

            // M31/M36/M37/M39. The key, what the cap has left, and — said here rather than
            // only in `licenses.md` — exactly what leaves the phone when the button is pressed.
            SectionHeader("Identification")
            SettingCard {
                Text(
                    text = IDENTIFICATION_KEY_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                Box(modifier = Modifier.height(10.dp))
                KeyField(
                    value = state.plantNetKey,
                    onValueChange = onPlantNetKey,
                    placeholder = "Paste your Pl@ntNet API key",
                )
                Text(
                    text = "Get a key at my.plantnet.org ↗",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { onOpenUrl(PlantNetIdentifier.KEY_SIGNUP_URL) },
                )
                Text(
                    text = state.identificationLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.identificationCapReached) colors.warn else colors.faint,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = IDENTIFICATION_PRIVACY_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionHeader("About")
            SettingCard {
                Text(
                    // §7's redline. The old sentence said photographs never leave the device,
                    // which stopped being true the moment Identify existed; saying so here,
                    // where a user looks for what the app does, is the point of the redline.
                    text = "BioDex 1.0 — Pacific USA BioDex, a personal life list of the " +
                        "region's animals, plants and fungi. Species text and images come from " +
                        "Wikipedia and Wikimedia Commons, and names from GBIF. Identification " +
                        "suggestions, when you ask for them, come from Pl@ntNet; only the " +
                        "photo you press Identify on is sent, reduced and without location " +
                        "data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                Box(modifier = Modifier.height(8.dp))
                // M30: the same sentence the uses section carries, repeated where a user
                // looks for what the app claims. It is the app's whole safety position.
                Text(
                    text = "Documented uses of the species — not identification, not safety " +
                        "advice. Never eat or use a plant on the strength of this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                Box(modifier = Modifier.height(8.dp))
                TextButton(onClick = onOpenLicenses) {
                    Text(text = "Licenses and attribution ›", color = colors.accent)
                }
            }

            Box(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * The key field, masked by default with a reveal toggle.
 *
 * An earlier version showed the key in full, on the reasoning that a masked field the user
 * cannot check against the one they were emailed turns a typo into a mystery. That reasoning
 * is sound and is why the toggle exists rather than a permanently masked field — but it is
 * not a reason to leave a credential legible by default on a screen the user might hand
 * across, screenshot, or screen-share. Masked is the safe default; Show is one tap away.
 */
@Composable
private fun KeyField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = DexTheme.colors
    // Not `rememberSaveable`: a revealed key must not survive into saved instance state,
    // and the field re-masking after a rotation is the right way to fail.
    var revealed by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.faint,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fg),
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (revealed) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Text(
                text = if (revealed) "Hide" else "Show",
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clickable { revealed = !revealed },
            )
        }
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    val colors = DexTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.rule, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun MessageCard(text: String, warning: Boolean) {
    val colors = DexTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) colors.warn else colors.ok,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (warning) colors.warnSoft else colors.accentSoft)
            .padding(12.dp),
    )
}

/** `.cta` — the accent-filled button. */
@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = DexTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = if (enabled) colors.card else colors.faint,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) colors.accent else colors.rule)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 14.dp),
    )
}

/** `.cta.ghost` — outlined. */
@Composable
private fun SecondaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = DexTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = if (enabled) colors.accent else colors.faint,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (enabled) colors.accent else colors.rule, RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 14.dp),
    )
}

/**
 * S01's share sheet. The archive lives in `cacheDir/exports` and is handed out through the
 * app's FileProvider, so the receiving app gets a `content://` URI with a read grant rather
 * than a `file://` path Android would refuse to pass.
 */
internal fun archiveChooserFor(uri: String, fileName: String): Intent {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        putExtra(Intent.EXTRA_TITLE, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(share, "Save your BioDex backup")
}
