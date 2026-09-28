package dev.tlong.biodex.ui.identify

import dev.tlong.biodex.domain.SearchMatch
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.ui.capture.PickedPhoto
import dev.tlong.biodex.ui.grid.addableNameFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

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
    /** The species whose name was typed in full — what Lens usually hands back. */
    val exactMatch: SpeciesSummary? = null,
    /** D80. Unticked for a zoo or aquarium; carried onto the capture either way. */
    val wild: Boolean = true,
    val capturing: Boolean = false,
) {
    /** D81: the one button registers a species the dex holds, picked or named in full. */
    val target: SpeciesSummary? get() = selected ?: exactMatch

    /** With no species to register, Register adds the typed name instead. */
    val registersNewSpecies: Boolean get() = target == null && addableName != null

    val canRegister: Boolean get() = !capturing && (target != null || addableName != null)

    val registerLabel: String
        get() {
            val species = target
            return when {
                capturing -> "Registering…"
                species != null -> "Register — ${species.commonName}"
                addableName != null -> "Register — add “$addableName”"
                else -> "Register"
            }
        }
}

fun identifyUiState(
    photo: PickedPhoto,
    species: Flow<List<SpeciesSummary>>,
    query: Flow<String>,
    selectedId: Flow<String?>,
    capturing: Flow<Boolean>,
    wild: Flow<Boolean> = flowOf(true),
): Flow<IdentifyUiState> =
    combine(species, query, selectedId, capturing, wild) { all, typed, id, busy, isWild ->
        val results = rankedMatches(all, typed)
        IdentifyUiState(
            photo = photo,
            query = typed,
            results = results,
            // From the whole dex, so a selection survives the search being edited.
            selected = all.firstOrNull { it.id == id },
            addableName = addableNameFor(all, typed),
            exactMatch = results.firstOrNull { bestRank(it, typed) == SearchMatch.EXACT },
            wild = isWild,
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
 * D78. The name in what was copied in Lens, or null when it does not look like one: the first
 * non-blank line, short, with a letter in it and no link. Lens copies can carry a second line
 * (a rank, a summary), and the name is the line that leads.
 */
internal fun nameFromClipboard(clip: String?): String? {
    val line = clip?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    val text = line.trimEnd('.', ',', ';', ':').trim()
    if (text.isEmpty() || text.length > 60 || text.none { it.isLetter() } || "://" in text) return null
    return text
}
