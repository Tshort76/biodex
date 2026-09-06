package dev.tlong.biodex.ui.nearest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import dev.tlong.biodex.appContainer
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.Lineage
import dev.tlong.biodex.domain.Neighbour
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.ui.common.HopBadge
import dev.tlong.biodex.ui.common.SectionHeader
import dev.tlong.biodex.ui.common.SilhouetteIcon
import dev.tlong.biodex.ui.theme.BioDexTheme
import dev.tlong.biodex.ui.theme.DexTheme

/**
 * D36's screen: the [NEAREST_COUNT] species in the dex closest to this one, and how many
 * hops away each is.
 *
 * It measures over **the catalogue**, not over all of life — the question is "how far do I
 * have to go, among the things I am collecting, before these two meet". An unregistered
 * neighbour is named by its scientific name only, the same bargain the grid strikes: you can
 * see that something is close without being told what it is.
 */
@Composable
fun NearestRoute(
    speciesId: String?,
    onBack: () -> Unit,
    onOpenSpecies: (String) -> Unit,
) {
    val container = LocalContext.current.appContainer
    val viewModel: NearestViewModel = viewModel(
        factory = NearestViewModel.factory(container, speciesId),
        key = "nearest/${speciesId ?: "recent"}",
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NearestScreen(state = state, onBack = onBack, onOpenSpecies = onOpenSpecies)
}

@Composable
fun NearestScreen(
    state: NearestUiState,
    onBack: () -> Unit,
    onOpenSpecies: (String) -> Unit,
) {
    val colors = DexTheme.colors
    Scaffold(containerColor = colors.bg) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                TextButton(onClick = onBack) {
                    Text(text = "‹ Back", color = colors.accent)
                }
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = "NEAREST",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.faint,
                    modifier = Modifier.padding(end = 14.dp),
                )
            }

            val focal = state.focal
            when {
                state.loading -> Unit

                // Both reasons land here: a species id that no longer resolves, and an
                // empty collection reached from the top bar before anything is classified.
                focal == null -> Message(
                    "Nothing to compare yet. Register a species, or open this from one.",
                )

                // A user-added species before its backfill. Saying so is the honest answer;
                // three neighbours at a distance we cannot measure would not be.
                state.unclassified -> {
                    FocalHeader(focal)
                    Message(
                        "This species has no classification yet, so there is nothing to " +
                            "measure against. Open it once while online and the backfill " +
                            "fills it in.",
                    )
                }

                else -> {
                    FocalHeader(focal)
                    SectionHeader(
                        text = "Closest in the dex",
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    state.neighbours.forEach { neighbour ->
                        NeighbourRow(
                            neighbour = neighbour,
                            onClick = { onOpenSpecies(neighbour.species.id) },
                        )
                    }
                    val overflow = state.overflowAtFurthest
                    val hops = state.furthestHops
                    if (overflow > 0 && hops != null) {
                        Footnote(
                            "${overflow + state.neighbours.count { it.hops == hops }} " +
                                "species are $hops hops away — the ones shown are simply " +
                                "the first, and no closer than the rest.",
                        )
                    }
                }
            }
        }
    }
}

/** The species the distances are measured from, on the accent ground so it reads as the anchor. */
@Composable
private fun FocalHeader(focal: SpeciesSummary) {
    val colors = DexTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentSoft)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(
            text = focal.commonName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        focal.scientificName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun NeighbourRow(neighbour: Neighbour, onClick: () -> Unit) {
    val colors = DexTheme.colors
    val species = neighbour.species
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        HopBadge(hops = neighbour.hops)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!species.caught) {
                    // The grid's bargain, kept here: an uncaught neighbour shows as its
                    // silhouette and its Latin name, so you can see something is close by
                    // without being handed what it is.
                    SilhouetteIcon(
                        silhouetteRes = species.silhouetteRes,
                        taxClass = species.taxClass,
                        size = 16.dp,
                        tint = colors.faint,
                    )
                }
                Text(
                    text = if (species.caught) species.commonName else "? ? ?",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (species.caught) colors.accent else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            species.scientificName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontStyle = FontStyle.Italic,
                    ),
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = hopMeaning(neighbour.hops),
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
            )
            neighbour.sharedTaxon?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.muted,
                )
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DexTheme.colors.muted,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DexTheme.colors.warnSoft)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = DexTheme.colors.muted,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
    )
}

@Preview
@Composable
private fun NearestPreview() {
    fun sp(id: String, name: String, sci: String, caught: Boolean, family: String) =
        SpeciesSummary(
            id = id,
            regionId = "pacific",
            dexNumber = id.hashCode() and 0xFF,
            source = SpeciesSource.CURATED,
            detailsPending = false,
            commonName = name,
            scientificName = sci,
            taxClass = TaxClass.MAMMAL,
            kingdom = Kingdom.ANIMAL,
            silhouetteRes = "sil_mammal",
            ecosystemIds = emptyList(),
            caughtAt = if (caught) 1L else null,
            thumbPath = null,
            captureCount = 0,
            lineage = Lineage("Animalia", "Chordata", "Mammalia", "Rodentia", family),
        )

    val focal = sp("a", "Douglas Squirrel", "Tamiasciurus douglasii", true, "Sciuridae")
    BioDexTheme {
        NearestScreen(
            state = NearestUiState(
                focal = focal,
                neighbours = listOf(
                    Neighbour(
                        sp("b", "Yellow-bellied Marmot", "Marmota flaviventris", true, "Sciuridae"),
                        2, "family", "Sciuridae",
                    ),
                    Neighbour(
                        sp("c", "American Beaver", "Castor canadensis", true, "Castoridae"),
                        4, "order", "Rodentia",
                    ),
                    Neighbour(
                        sp("d", "Virginia Opossum", "Didelphis virginiana", false, "Didelphidae"),
                        6, "class", "Mammalia",
                    ),
                ),
                tiedAtFurthest = 15,
                loading = false,
            ),
            onBack = {},
            onOpenSpecies = {},
        )
    }
}
