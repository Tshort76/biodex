package dev.tlong.biodex.ui.identify

import dev.tlong.biodex.data.net.LookupOutcome
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
    /** D88: what the online lookup made of the name, before any place is asked for. */
    val online: OnlineLookup = OnlineLookup.Idle,
    /** D88: the dex species the online lookup named — by its scientific name, not the typed one. */
    val onlineMatch: SpeciesSummary? = null,
) {
    /** D81: the one button registers a species the dex holds, picked, named in full, or found online. */
    val target: SpeciesSummary? get() = selected ?: exactMatch ?: onlineMatch

    /** The lookup the add card would otherwise repeat, when it was for the name being added. */
    val prefetched: LookupOutcome?
        get() = (online as? OnlineLookup.Done)?.takeIf { it.name == addableName }?.outcome

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

/**
 * D88. The online lookup's progress for one name. It runs on the Identify screen, as the name is
 * typed, so a wrong name is found out and fixed before the place prompt rather than after it.
 */
sealed interface OnlineLookup {
    data object Idle : OnlineLookup

    data class Searching(val name: String) : OnlineLookup

    data class Done(val name: String, val outcome: LookupOutcome) : OnlineLookup

    data object Offline : OnlineLookup
}

fun identifyUiState(
    photo: PickedPhoto,
    species: Flow<List<SpeciesSummary>>,
    query: Flow<String>,
    selectedId: Flow<String?>,
    capturing: Flow<Boolean>,
    wild: Flow<Boolean> = flowOf(true),
    online: Flow<OnlineLookup> = flowOf(OnlineLookup.Idle),
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
            exactMatch = results.firstOrNull { (bestRank(it, typed) ?: Int.MAX_VALUE) <= SearchMatch.WITHIN },
            wild = isWild,
            capturing = busy,
        ) to all
    }.combine(online) { (state, all), lookup ->
        state.copy(online = lookup, onlineMatch = onlineMatchFor(all, lookup, state.query))
    }

/**
 * D88. The name to look up online, or null when there is nothing to ask: nothing typed, a
 * species already picked, or a name the dex holds in full.
 */
internal fun onlineLookupName(species: List<SpeciesSummary>, query: String, selectedId: String?): String? {
    val name = query.trim()
    if (SearchMatch.fold(name).isEmpty() || selectedId != null) return null
    val held = species.any { (bestRank(it, name) ?: Int.MAX_VALUE) <= SearchMatch.WITHIN }
    return name.takeUnless { held }
}

/** D88. The dex species whose scientific name is the one the lookup resolved, if any. */
internal fun onlineMatchFor(species: List<SpeciesSummary>, lookup: OnlineLookup, query: String): SpeciesSummary? {
    val done = lookup as? OnlineLookup.Done ?: return null
    if (done.name != query.trim()) return null
    val resolved = (done.outcome as? LookupOutcome.Resolved)?.selected?.scientificName ?: return null
    val key = SearchMatch.binomial(resolved)
    return species.firstOrNull { s -> s.scientificName?.let(SearchMatch::binomial) == key }
}

/** D88. One line on what the lookup found, for under the name; null while there is nothing to say. */
data class OnlineLine(val text: String, val imageUrl: String? = null, val warning: Boolean = false)

fun onlineLine(state: IdentifyUiState): OnlineLine? = when (val lookup = state.online) {
    OnlineLookup.Idle -> null
    OnlineLookup.Offline -> OnlineLine("Offline — the name is checked online when you add it.")
    is OnlineLookup.Searching -> OnlineLine("Searching online for “${lookup.name}”…")
    is OnlineLookup.Done -> when (val outcome = lookup.outcome) {
        is LookupOutcome.Resolved -> {
            val found = outcome.selected
            val named = listOfNotNull(found.commonName, found.scientificName).distinct().joinToString(" · ")
            val text = state.onlineMatch?.let { "Online: $named — that is ${it.commonName} in your dex." }
                ?: "Online: $named (${found.confidenceLabel})."
            OnlineLine(text, imageUrl = outcome.details.fields.imageUrl, warning = !found.isNameMatch)
        }
        LookupOutcome.NoMatch -> OnlineLine("Nothing online goes by “${lookup.name}” — try another name.", warning = true)
        is LookupOutcome.Failed -> OnlineLine("Could not search online (${outcome.reason}).", warning = true)
    }
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
