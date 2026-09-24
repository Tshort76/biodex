package dev.tlong.biodex.ui.addspecies

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
import androidx.compose.ui.text.style.TextAlign
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
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.SpeciesField
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.defaultSilhouetteFor
import dev.tlong.biodex.ui.common.AttributionLine
import dev.tlong.biodex.ui.common.SectionHeader
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.common.PrimaryCta
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * Frame 6 of `mockup.html`: the GBIF best-match card with its alternatives link, the found
 * image and its credit, the found habitat text with an edit affordance,
 * the manual ecosystem multi-select, and the accept button naming the U-number the species is
 * about to take. Accepting adds the species and nothing more; the screen then asks whether it
 * has been caught (D69).
 */
@Composable
fun ConfirmSpeciesRoute(
    draftId: String,
    onBack: () -> Unit,
    /** D69: added, not caught yet. */
    onNotCaught: (speciesId: String) -> Unit,
    /** D69: added and caught — to its entry, where the photo is registered. */
    onCaught: (speciesId: String) -> Unit,
    /** D78: added and captured with the Identify screen's photo; the route plays the reveal. */
    onAddedAndCaptured: (speciesId: String) -> Unit = onCaught,
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
                is ConfirmSpeciesViewModel.Event.NotCaught -> onNotCaught(event.speciesId)
                is ConfirmSpeciesViewModel.Event.Caught -> onCaught(event.speciesId)
                // The same move as "Yes": open that species' entry, with the grid behind it.
                is ConfirmSpeciesViewModel.Event.OpenExisting -> onCaught(event.speciesId)
                is ConfirmSpeciesViewModel.Event.AddedAndCaptured -> onAddedAndCaptured(event.speciesId)
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
        onAccept = viewModel::onAccept,
        onNotCaught = viewModel::onNotCaught,
        onCaught = viewModel::onCaught,
        onOpenExisting = viewModel::onOpenExisting,
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
    onAccept: () -> Unit,
    onNotCaught: () -> Unit = {},
    onCaught: () -> Unit = {},
    onOpenExisting: () -> Unit = {},
) {
    val colors = DexTheme.colors
    // D69. Once the species is written, Back means "not yet" — never a return to a card that
    // would add it a second time.
    BackHandler(enabled = state is ConfirmSpeciesUiState.Added, onBack = onNotCaught)
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
                    modifier = Modifier.clickable(
                        onClick = if (state is ConfirmSpeciesUiState.Added) onNotCaught else onBack,
                    ),
                )
                Text(
                    text = if ((state as? ConfirmSpeciesUiState.Card)?.isBackfill == true) {
                        "Fill In the Details"
                    } else {
                        "Add a Species"
                    },
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
                        "Search the name again to start over.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )

                is ConfirmSpeciesUiState.Added -> AddedBody(
                    state = state,
                    onNotCaught = onNotCaught,
                    onCaught = onCaught,
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
                    onAccept = onAccept,
                    onOpenExisting = onOpenExisting,
                )
            }
        }
    }
}

/** D69. "Did you mean #052 Western Tanager? Open it ›" — a suggestion, never a block. */
@Composable
private fun NearMissLink(near: HeldSpecies, onOpen: () -> Unit) {
    Text(
        text = "Did you mean ${near.title}? Open it ›",
        style = MaterialTheme.typography.labelMedium,
        color = DexTheme.colors.accent,
        modifier = Modifier
            .padding(top = 6.dp)
            .clickable(onClick = onOpen),
    )
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
    onAccept: () -> Unit,
    onOpenExisting: () -> Unit,
) {
    val colors = DexTheme.colors

    // D73. A new species the lookup did not find is not added — the card says why and stops.
    val notFound = !state.isBackfill && !state.found && (state.lookupFailed || state.noMatch)
    if (state.lookupFailed || state.noMatch) {
        Text(
            text = when {
                !state.isBackfill && state.noMatch ->
                    "Nothing online matches “${state.typedName}” — check the spelling and search again."
                !state.isBackfill -> "Couldn't reach the lookup — try again when you're online."
                state.noMatch -> "Nothing in GBIF matches “${state.typedName}”. Fill in the details by hand."
                else -> "Could not reach the lookup services. Try again online, or fill in the details by hand."
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

    if (notFound) {
        state.nearMiss?.let { near -> NearMissLink(near, onOpenExisting) }
        return
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
    // hand" — for a fungus it is one of the fields the card exists to ask about (D27). An
    // animal keeps slice 7's card exactly, with its class picker inside the hand-edit block.
    if (state.isFungus || state.handEditing) {
        KindSection(
            state = state,
            onToggleKingdom = onToggleKingdom,
            onSelectTaxClass = onSelectTaxClass,
        )
    }

    SectionHeader("Ecosystems · optional")
    EcosystemChips(
        ecosystems = state.ecosystems,
        selected = state.selectedEcosystemIds,
        onToggle = onToggleEcosystem,
    )

    if (state.handEditing) {
        SectionHeader("Name and identity")
        FieldEditor(
            value = state.typedCommonName ?: state.fields.commonName,
            placeholder = "Common name",
            onValueChange = { typed ->
                onEditField(SpeciesField.COMMON_NAME) { it.copy(commonName = typed) }
            },
        )
        FieldEditor(
            value = (state.typedScientificName ?: state.fields.scientificName).orEmpty(),
            placeholder = "Scientific name",
            onValueChange = { typed ->
                onEditField(SpeciesField.SCIENTIFIC_NAME) {
                    it.copy(scientificName = typed.ifBlank { null })
                }
            },
        )
    }

    if (state.willBeDetailsPending && !state.isBackfill) {
        Text(
            text = "No match yet — the app tries again next time you're online.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.faint,
        )
    }

    val held = state.alreadyHeld
    if (held != null) {
        Text(
            text = "${held.title} is already in your dex.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fg,
            modifier = Modifier.padding(top = 6.dp),
        )
        PrimaryCta(label = "Open ${held.title}", enabled = true, onClick = onOpenExisting)
    } else {
        state.nearMiss?.let { near -> NearMissLink(near, onOpenExisting) }
        PrimaryCta(
            label = if (state.saving) "Saving…" else state.acceptLabel,
            enabled = state.canAccept,
            onClick = onAccept,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    Text(
        text = if (state.handEditing) "Done editing by hand" else "Edit all details by hand",
        style = MaterialTheme.typography.labelMedium,
        color = colors.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggleHandEditing)
            .padding(vertical = 12.dp),
    )

    state.fields.imageAttribution?.let { AttributionLine(it, Modifier.padding(bottom = 24.dp)) }
}

/**
 * D69's question. The species already exists, so both answers are navigation: the photo that
 * proves a catch is registered from the entry, the same way as for any catalogue species.
 */
@Composable
private fun AddedBody(
    state: ConfirmSpeciesUiState.Added,
    onNotCaught: () -> Unit,
    onCaught: () -> Unit,
) {
    val colors = DexTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 24.dp),
    ) {
        Text(
            text = "${state.title} is in your dex.",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.fg,
        )
        Text(
            text = "Have you caught it?",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.fg,
            modifier = Modifier.padding(top = 8.dp),
        )
        PrimaryCta(label = "Yes — register my photo", enabled = true, onClick = onCaught)
        Text(
            text = "Not yet",
            style = MaterialTheme.typography.labelLarge,
            color = colors.muted,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.rule, RoundedCornerShape(12.dp))
                .clickable(onClick = onNotCaught)
                .padding(vertical = 14.dp),
            textAlign = TextAlign.Center,
        )
    }
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
            silhouetteRes = defaultSilhouetteFor(candidate.taxClass),
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
                // D30, same rule as the detail hero: this is the image the pipeline pulled
                // from Wikimedia, and cropping it to fill is how the confirmation card came
                // to show a bird's wing. The frame is the species' first impression.
                contentScale = ContentScale.Fit,
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
        label = if (state.isFungus) "Growth form · your pick" else "Kingdom and class",
        edited = state.isEdited(SpeciesField.TAX_CLASS) || state.isEdited(SpeciesField.KINGDOM),
        action = (if (state.isFungus) "not a fungus?" else "a fungus?") to onToggleKingdom,
    )
    if (state.isFungus) {
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
