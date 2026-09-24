package dev.tlong.biodex.ui.identify

import dev.tlong.biodex.domain.SearchMatch
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.ui.capture.PickedPhoto
import dev.tlong.biodex.ui.grid.addableNameFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * D78. The Identify screen: one photo in hand, a name to find for it. The screen offers Lens
 * for the finding and a search over the dex for the naming; what it produces is either a
 * capture of a species the dex holds or an add of one it does not, both with this photo.
 * Pure, like every screen's state here (ARCHITECTURE.md 6.2).
 */
data class IdentifyUiState(
    val photo: PickedPhoto,
    val query: String = "",
    /** Best match first (D74); empty until something is typed. */
    val results: List<SpeciesSummary> = emptyList(),
    val selected: SpeciesSummary? = null,
    /** D69's rule: the search names no species in the dex, so it is a species to add. */
    val addableName: String? = null,
    /** Text copied in Lens, offered back once as the search (D78). */
    val clipboardOffer: String? = null,
    val capturing: Boolean = false,
) {
    val canCapture: Boolean get() = selected != null && !capturing

    val captureLabel: String get() = selected?.let { "Capture — ${it.commonName}" } ?: "Capture"
}

fun identifyUiState(
    photo: PickedPhoto,
    species: Flow<List<SpeciesSummary>>,
    query: Flow<String>,
    selectedId: Flow<String?>,
    clipboard: Flow<String?>,
    capturing: Flow<Boolean>,
): Flow<IdentifyUiState> =
    combine(species, query, selectedId, clipboard, capturing) { all, typed, id, clip, busy ->
        IdentifyUiState(
            photo = photo,
            query = typed,
            results = rankedMatches(all, typed),
            // From the whole dex, so a selection survives the search being edited.
            selected = all.firstOrNull { it.id == id },
            addableName = addableNameFor(all, typed),
            clipboardOffer = clipboardOffer(clip, typed),
            capturing = busy,
        )
    }

/** D74. Best match first, dex number breaking ties; nothing until something is typed. */
internal fun rankedMatches(species: List<SpeciesSummary>, query: String): List<SpeciesSummary> {
    if (query.isBlank()) return emptyList()
    return species.mapNotNull { s -> bestRank(s, query)?.let { s to it } }
        .sortedWith(compareBy({ it.second }, { it.first.dexNumber }))
        .map { it.first }
}

private fun bestRank(species: SpeciesSummary, query: String): Int? =
    listOfNotNull(
        SearchMatch.rank(species.commonName, query),
        species.scientificName?.let { SearchMatch.rank(it, query) },
    ).minOrNull()

/**
 * D78. What the clipboard holds, when it looks like a name worth offering: one line, short,
 * with a letter in it, and not already what the search says. A copied paragraph or URL is not
 * a species name, and offering the search back to itself is noise.
 */
internal fun clipboardOffer(clip: String?, query: String): String? {
    val text = clip?.trim()?.trimEnd('.', ',', ';', ':')?.trim() ?: return null
    if (text.isEmpty() || text.length > 60 || '\n' in text || text.none { it.isLetter() }) return null
    if ("://" in text) return null
    if (SearchMatch.fold(text) == SearchMatch.fold(query)) return null
    return text
}
