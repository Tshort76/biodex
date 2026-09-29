package dev.tlong.biodex.ui.nearest

import dev.tlong.biodex.domain.Neighbour
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxonDistance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * How many neighbours the screen names (D36).
 *
 * Three rather than five because the five were never a ranking: everything in one family is
 * the same 2 hops away, so which ones surface is arbitrary and a longer arbitrary list is
 * only more of the same. The footnote carries the count that actually matters.
 */
const val NEAREST_COUNT = 3

/**
 * Nearest Five's state (D36). A pure function over the repository's cold flows, like every
 * other screen here, so the JVM suite exercises the whole thing with no device and no Room
 * (ARCHITECTURE.md 6.2).
 */
data class NearestUiState(
    val focal: SpeciesSummary? = null,
    val neighbours: List<Neighbour> = emptyList(),
    /** Every species in the dex sitting exactly [furthestHops] away, not just the shown ones. */
    val tiedAtFurthest: Int = 0,
    val loading: Boolean = true,
) {
    val missing: Boolean get() = !loading && focal == null

    /**
     * True for a species with no lineage — every user-added one until its backfill. The
     * screen says so plainly instead of drawing three neighbours at a distance it cannot
     * actually measure.
     */
    val unclassified: Boolean
        get() = !loading && focal != null && !focal.lineage.isKnown

    val furthestHops: Int? get() = neighbours.lastOrNull()?.hops

    /**
     * How many more species share the furthest shown distance. The three on screen are an
     * arbitrary three of these, and the footnote exists to say so rather than let the list
     * imply it singled them out.
     */
    val overflowAtFurthest: Int
        get() {
            val shown = neighbours.count { it.hops == furthestHops }
            return (tiedAtFurthest - shown).coerceAtLeast(0)
        }
}

/** The species whose entry opened the screen (D87), and the ones closest to it. */
fun nearestUiState(
    species: Flow<List<SpeciesSummary>>,
    speciesId: String,
): Flow<NearestUiState> = species.map { all ->
    val focal = all.firstOrNull { it.id == speciesId }
        ?: return@map NearestUiState(loading = false)
    val neighbours = TaxonDistance.nearest(focal, all, NEAREST_COUNT)
    NearestUiState(
        focal = focal,
        neighbours = neighbours,
        tiedAtFurthest = neighbours.lastOrNull()
            ?.let { TaxonDistance.countAt(focal, all, it.hops) }
            ?: 0,
        loading = false,
    )
}

/** What a hop count means, in the words a person would use. */
fun hopMeaning(hops: Int): String = when (hops) {
    2 -> "same family"
    4 -> "same order"
    6 -> "same class"
    8 -> "same phylum"
    10 -> "same kingdom"
    else -> "different kingdoms"
}
