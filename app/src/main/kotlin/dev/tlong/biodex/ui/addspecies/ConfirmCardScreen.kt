package dev.tlong.biodex.ui.addspecies

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.data.net.MatchKind
import dev.tlong.biodex.data.net.SpeciesCandidate
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.SpeciesField
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.defaultSilhouetteFor
import dev.tlong.biodex.ui.common.AttributionLine
import dev.tlong.biodex.ui.common.SectionHeader
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.register.PrimaryCta
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * Frame 6 of `mockup.html`: the GBIF best-match card with its alternatives link, the found
 * image and its credit, the found habitat text with an edit affordance, the call-found row,
 * the manual ecosystem multi-select, and the accept button naming the U-number the species is
 * about to take.
 */
@Composable
fun ConfirmSpeciesRoute(
    draftId: String,
    onBack: () -> Unit,
    onCreated: (speciesId: String) -> Unit,
    onUpdated: (speciesId: String) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val viewModel: ConfirmSpeciesViewModel = viewModel(
        key = draftId,
        factory = ConfirmSpeciesViewModel.factory(container, draftId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ConfirmSpeciesViewModel.Event.Created -> onCreated(event.speciesId)
                is ConfirmSpeciesViewModel.Event.Updated -> onUpdated(event.speciesId)
                ConfirmSpeciesViewModel.Event.Dismissed -> onBack()
            }
        }
    }

    ConfirmSpeciesScreen(
        state = state,
        onBack = onBack,
        onSelectCandidate = viewModel::onSelectCandidate,
        onToggleAlternatives = viewModel::onToggleAlternatives,
        onToggleEcosystem = viewModel::onToggleEcosystem,
        onToggleHandEditing = viewModel::onToggleHandEditing,
        onEditField = viewModel::onEditField,
        onToggleKingdom = viewModel::onToggleKingdom,
        onSelectTaxClass = viewModel::onSelectTaxClass,
        onToggleUse = viewModel::onToggleUse,
        onEditUsesNote = viewModel::onEditUsesNote,
        onAccept = viewModel::onAccept,
    )
}

@Composable
fun ConfirmSpeciesScreen(
    state: ConfirmSpeciesUiState,
    onBack: () -> Unit,
    onSelectCandidate: (Int) -> Unit,
    onToggleAlternatives: () -> Unit,
    onToggleEcosystem: (String) -> Unit,
    onToggleHandEditing: () -> Unit,
    onEditField: (String, (SpeciesFields) -> SpeciesFields) -> Unit,
    onToggleKingdom: () -> Unit,
    onSelectTaxClass: (TaxClass) -> Unit,
    onToggleUse: (PlantUse) -> Unit,
    onEditUsesNote: (String) -> Unit,
    onAccept: () -> Unit,
) {
    val colors = DexTheme.colors
    Scaffold(containerColor = colors.bg) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    text = "Add Your Own Species",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.fg,
                )
            }

            when (state) {
                ConfirmSpeciesUiState.Loading -> Text(
                    text = "Looking this one up…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )

                ConfirmSpeciesUiState.Missing -> Text(
                    text = "This draft is gone — the app restarted before it was saved. " +
                        "Register the photo again to start over.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )

                is ConfirmSpeciesUiState.Card -> CardBody(
                    state = state,
                    onSelectCandidate = onSelectCandidate,
                    onToggleAlternatives = onToggleAlternatives,
                    onToggleEcosystem = onToggleEcosystem,
                    onToggleHandEditing = onToggleHandEditing,
                    onEditField = onEditField,
                    onToggleKingdom = onToggleKingdom,
                    onSelectTaxClass = onSelectTaxClass,
                    onToggleUse = onToggleUse,
                    onEditUsesNote = onEditUsesNote,
                    onAccept = onAccept,
                )
            }
        }
    }
}

@Composable
private fun CardBody(
    state: ConfirmSpeciesUiState.Card,
    onSelectCandidate: (Int) -> Unit,
    onToggleAlternatives: () -> Unit,
    onToggleEcosystem: (String) -> Unit,
    onToggleHandEditing: () -> Unit,
    onEditField: (String, (SpeciesFields) -> SpeciesFields) -> Unit,
    onToggleKingdom: () -> Unit,
    onSelectTaxClass: (TaxClass) -> Unit,
    onToggleUse: (PlantUse) -> Unit,
    onEditUsesNote: (String) -> Unit,
    onAccept: () -> Unit,
) {
    val colors = DexTheme.colors

    if (state.lookupFailed || state.noMatch) {
        Text(
            text = if (state.noMatch) {
                "Nothing in GBIF matches “${state.typedName}”. You can name it yourself, or " +
                    "save it now and let the app try again later."
            } else {
                "Could not reach the lookup services. Save it now — the app will fill the " +
                    "details in the next time you open it online."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.warn,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.warnSoft)
                .padding(10.dp),
        )
    }

    SectionHeader("Best match · GBIF")
    val best = state.selectedCandidate
    if (best != null) {
        CandidateRow(candidate = best, selected = true, onClick = {})
    } else {
        Text(
            text = state.fields.commonName,
            style = MaterialTheme.typography.titleSmall,
            color = colors.fg,
        )
    }

    state.alternativesLabel?.let { label ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.accent,
            modifier = Modifier
                .padding(vertical = 2.dp)
                .clickable(onClick = onToggleAlternatives),
        )
    }
    if (state.showAlternatives) {
        state.candidates.forEachIndexed { index, candidate ->
            if (index != state.selectedIndex) {
                CandidateRow(
                    candidate = candidate,
                    selected = false,
                    onClick = { onSelectCandidate(index) },
                )
            }
        }
    }

    EditableSectionHeader(
        label = "Image · Wikipedia",
        edited = state.isEdited(SpeciesField.IMAGE_URL),
        action = null,
    )
    MiniHero(state)

    EditableSectionHeader(
        label = state.habitatLabel,
        edited = state.isEdited(SpeciesField.HABITAT_TEXT),
        action = if (state.handEditing) null else "✎ edit" to onToggleHandEditing,
    )
    if (state.handEditing) {
        FieldEditor(
            value = state.fields.habitatText.orEmpty(),
            placeholder = "Where does it live?",
            onValueChange = { typed ->
                onEditField(SpeciesField.HABITAT_TEXT) { it.copy(habitatText = typed) }
            },
        )
    } else {
        Text(
            text = state.fields.habitatText ?: "No habitat text found — you can write your own.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.habitatFound) colors.fg else colors.faint,
        )
    }

    // M19/M27: the growth form is a pick on the card, not something hidden behind "edit by
    // hand" — for a plant it is one of the fields the card exists to ask about. An animal keeps
    // slice 7's card exactly, with its class picker still inside the hand-edit block.
    if (state.isPlant || state.handEditing) {
        KindSection(
            state = state,
            onToggleKingdom = onToggleKingdom,
            onSelectTaxClass = onSelectTaxClass,
        )
    }

    if (state.isPlant) {
        UsesEditor(state = state, onToggleUse = onToggleUse, onEditUsesNote = onEditUsesNote)
    } else {
        SectionHeader("Call")
        Text(
            text = listOfNotNull(state.callRowLabel, state.callAttribution).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.callFound) colors.ok else colors.faint,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.codeBg)
                .padding(10.dp),
        )
    }

    SectionHeader("Ecosystems · your pick")
    Text(
        text = "No lookup can tell us which of these seven a species belongs to — this one is " +
            "yours to choose, and leaving it empty is fine.",
        style = MaterialTheme.typography.labelSmall,
        color = colors.faint,
    )
    EcosystemChips(
        ecosystems = state.ecosystems,
        selected = state.selectedEcosystemIds,
        onToggle = onToggleEcosystem,
    )

    if (state.handEditing) {
        SectionHeader("Name and identity")
        FieldEditor(
            value = state.fields.commonName,
            placeholder = "Common name",
            onValueChange = { typed ->
                onEditField(SpeciesField.COMMON_NAME) { it.copy(commonName = typed) }
            },
        )
        FieldEditor(
            value = state.fields.scientificName.orEmpty(),
            placeholder = "Scientific name",
            onValueChange = { typed ->
                onEditField(SpeciesField.SCIENTIFIC_NAME) {
                    it.copy(scientificName = typed.ifBlank { null })
                }
            },
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

    if (state.willBeDetailsPending && !state.isBackfill) {
        Text(
            text = "Saved without a match, this entry stays “details pending” and the app " +
                "tries the lookup again the next time you open it online.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.faint,
        )
    }

    PrimaryCta(
        label = if (state.saving) "Saving…" else state.acceptLabel,
        enabled = state.canAccept,
        onClick = onAccept,
        modifier = Modifier.padding(top = 6.dp),
    )

    Text(
        text = if (state.handEditing) "Done editing by hand" else "Edit all details by hand",
        style = MaterialTheme.typography.labelMedium,
        color = colors.faint,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggleHandEditing)
            .padding(vertical = 12.dp)
            .padding(bottom = 0.dp),
    )

    state.fields.imageAttribution?.let { AttributionLine(it, Modifier.padding(bottom = 24.dp)) }
}

@Composable
private fun CandidateRow(
    candidate: SpeciesCandidate,
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
                width = 1.dp,
                color = if (selected) colors.accent else colors.rule,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        SilhouetteIcon(
            silhouetteRes = candidate.silhouetteResOverride
                ?: defaultSilhouetteFor(candidate.taxClass),
            taxClass = candidate.taxClass,
            size = 26.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.commonName ?: candidate.scientificName,
                style = MaterialTheme.typography.titleSmall,
                color = colors.fg,
            )
            Text(
                text = "${candidate.scientificName} · ${candidate.kingdom.wireName} · " +
                    candidate.taxClass.wireName,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
        Text(
            text = candidate.confidenceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (candidate.matchKind == MatchKind.EXACT ||
                candidate.matchKind == MatchKind.VERNACULAR_EXACT
            ) {
                colors.ok
            } else {
                colors.warn
            },
        )
    }
}

@Composable
private fun MiniHero(state: ConfirmSpeciesUiState.Card) {
    val colors = DexTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.silBg),
    ) {
        val url = state.fields.imageUrl
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = state.fields.commonName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SilhouetteIcon(
                silhouetteRes = state.fields.silhouetteRes,
                taxClass = state.fields.taxClass,
                size = 64.dp,
            )
        }
    }
    if (!state.imageFound) {
        Text(
            text = "No image found — the class silhouette stands in.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.faint,
        )
    }
}

@Composable
private fun EditableSectionHeader(label: String, edited: Boolean, action: Pair<String, () -> Unit>?) {
    val colors = DexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SectionHeader(label, Modifier.weight(1f))
        if (edited) {
            Text(
                text = "your edit",
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
            )
        }
        action?.let { (text, onClick) ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
                modifier = Modifier.clickable(onClick = onClick),
            )
        }
    }
}

@Composable
private fun FieldEditor(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val colors = DexTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
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
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fg),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The kingdom and the growth form (M27). The kingdom is GBIF's answer, shown rather than
 * hidden, with a toggle for the case GBIF got it wrong; the class picker offers that kingdom's
 * classes and nothing else, so a sparrow is never offered "tree".
 */
@Composable
private fun KindSection(
    state: ConfirmSpeciesUiState.Card,
    onToggleKingdom: () -> Unit,
    onSelectTaxClass: (TaxClass) -> Unit,
) {
    val colors = DexTheme.colors
    EditableSectionHeader(
        label = if (state.isPlant) "Growth form · your pick" else "Kingdom and class",
        edited = state.isEdited(SpeciesField.TAX_CLASS) || state.isEdited(SpeciesField.KINGDOM),
        action = (if (state.isPlant) "not a plant?" else "a plant?") to onToggleKingdom,
    )
    if (state.isPlant) {
        Text(
            text = "GBIF names the species; how it grows is a judgment call it cannot make, so " +
                "this one is yours.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.faint,
        )
    }
    TaxClassPicker(
        offered = state.offeredClasses,
        selected = state.fields.taxClass,
        onSelect = onSelectTaxClass,
    )
}

/**
 * The uses editor (M27) — a plant's half of the card, standing where an animal's call row is.
 *
 * The medicinal toggle is defaulted from the bundled Duke's index and the caution sentence is
 * pre-filled from a `Poison` record, but **edible is never defaulted on**: Duke's holds almost
 * no food records, so an edible claim could only come from this app, and D14 and M30 are what
 * stop it doing that. Both toggles stay the user's to set.
 */
@Composable
private fun UsesEditor(
    state: ConfirmSpeciesUiState.Card,
    onToggleUse: (PlantUse) -> Unit,
    onEditUsesNote: (String) -> Unit,
) {
    val colors = DexTheme.colors
    EditableSectionHeader(
        label = "Uses",
        edited = state.isEdited(SpeciesField.USES) || state.isEdited(SpeciesField.USES_NOTE),
        action = null,
    )
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PlantUse.entries.forEach { use ->
            val on = state.hasUse(use)
            val tint = if (use == PlantUse.EDIBLE) colors.ok else colors.accent
            Text(
                text = if (on) "${use.label} ✓" else use.label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = if (on) tint else colors.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) colors.accentSoft else colors.card)
                    .border(1.dp, if (on) tint else colors.rule, RoundedCornerShape(999.dp))
                    .clickable { onToggleUse(use) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }

    Text(
        text = state.dukeLabel,
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeBg)
            .padding(10.dp),
    )

    state.noteCaution?.let { caution ->
        Text(
            text = "⚠ $caution",
            style = MaterialTheme.typography.bodySmall,
            color = colors.stop,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.stopSoft)
                .padding(10.dp),
        )
    }
    // The note field appears only once there is a tag to hang it on. `usesNote` is null
    // whenever `uses` is empty (11.1), so an editor offered before then would swallow every
    // keystroke it was given — the field would look live and save nothing.
    if (state.uses.isEmpty()) {
        Text(
            text = "Tag a use to add a note.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.faint,
        )
    } else {
        FieldEditor(
            value = state.fields.usesNote.orEmpty(),
            placeholder = "Which part, when — and any caution",
            onValueChange = onEditUsesNote,
        )
    }

    if (state.cautionWillBeDropped) {
        Text(
            text = "Duke's records this species as poisonous. The note is only saved with a use " +
                "tag, so tag it or the caution goes with it.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.warn,
        )
    }

    Text(
        text = "Documented uses of the species — not identification, not safety advice. Never " +
            "eat or use a plant on the strength of this app.",
        style = MaterialTheme.typography.labelSmall,
        color = colors.faint,
    )
}

@Composable
private fun TaxClassPicker(
    offered: List<TaxClass>,
    selected: TaxClass,
    onSelect: (TaxClass) -> Unit,
) {
    val colors = DexTheme.colors
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        offered.forEach { taxClass ->
            val on = taxClass == selected
            Text(
                text = taxClass.wireName.replace('_', ' '),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = if (on) colors.accent else colors.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) colors.accentSoft else colors.card)
                    .border(
                        width = 1.dp,
                        color = if (on) colors.accent else colors.rule,
                        shape = RoundedCornerShape(999.dp),
                    )
                    .clickable { onSelect(taxClass) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EcosystemChips(
    ecosystems: List<Ecosystem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val colors = DexTheme.colors
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ecosystems.forEach { ecosystem ->
            val on = ecosystem.id in selected
            Text(
                text = if (on) "${ecosystem.name} ✓" else ecosystem.name,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = if (on) colors.accent else colors.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) colors.accentSoft else colors.card)
                    .border(
                        width = 1.dp,
                        color = if (on) colors.accent else colors.rule,
                        shape = RoundedCornerShape(999.dp),
                    )
                    .clickable { onToggle(ecosystem.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (risk R6: nothing renders them here, but they compile and they name
// the states the screen has).
// ---------------------------------------------------------------------------

@Preview(name = "Confirm card — populated", showBackground = true)
@Composable
private fun PreviewConfirmCard() {
    BioDexTheme {
        ConfirmSpeciesScreen(
            state = previewCard(),
            onBack = {},
            onSelectCandidate = {},
            onToggleAlternatives = {},
            onToggleEcosystem = {},
            onToggleHandEditing = {},
            onEditField = { _, _ -> },
            onToggleKingdom = {},
            onSelectTaxClass = {},
            onToggleUse = {},
            onEditUsesNote = {},
            onAccept = {},
        )
    }
}

private fun previewCard() = ConfirmSpeciesUiState.Card(
    typedName = "Varied Thrush",
    isBackfill = false,
    candidates = listOf(
        SpeciesCandidate(
            scientificName = "Ixoreus naevius",
            commonName = "Varied Thrush",
            taxClass = TaxClass.BIRD,
            confidence = 100,
            matchKind = MatchKind.VERNACULAR_EXACT,
        ),
    ),
    selectedIndex = 0,
    showAlternatives = false,
    fields = SpeciesFields(
        commonName = "Varied Thrush",
        scientificName = "Ixoreus naevius",
        taxClass = TaxClass.BIRD,
        habitatText = "Breeds in dense, moist coniferous forest along the Pacific coast.",
    ),
    editedFields = emptySet(),
    habitatSource = "wikipedia:section:Distribution and habitat",
    callAttribution = null,
    duke = null,
    dukeConsulted = false,
    lookupFailed = false,
    noMatch = false,
    ecosystems = listOf(
        Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
        Ecosystem("urban-suburban", "pacific", "Urban & Suburban", 7),
    ),
    selectedEcosystemIds = setOf("coastal-rainforest"),
    dexNumber = 1004,
    handEditing = false,
    saving = false,
)

/** The chip words for the two use tags; the enum's wire names are storage, not copy. */
private val PlantUse.label: String
    get() = when (this) {
        PlantUse.EDIBLE -> "Edible"
        PlantUse.MEDICINAL -> "Medicinal"
    }
