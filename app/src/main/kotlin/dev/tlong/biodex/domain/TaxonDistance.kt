package dev.tlong.biodex.domain

/**
 * D36's measure: how many hops through the taxonomy separate two species in the dex.
 *
 * The tree is `Life > kingdom > phylum > class > order > family > species`, so a species
 * sits at depth 6. Both ends of any comparison are species, which is what makes the
 * distance simply twice the climb to their last shared rank — always even, and always one
 * of six values:
 *
 * | hops | meaning |
 * |---|---|
 * | 2 | same family |
 * | 4 | same order |
 * | 6 | same class |
 * | 8 | same phylum |
 * | 10 | same kingdom |
 * | 12 | nothing shared but life itself |
 *
 * Douglas Squirrel to Black-tailed Jackrabbit is 6: up through Sciuridae and Rodentia to
 * Mammalia, then back down through Lagomorpha and Leporidae.
 *
 * This measures over **the catalogue**, not over all of life. Two species 6 apart here are
 * not 6 apart in nature — the number answers "how far do I have to go, among the things I
 * am collecting, before these two meet", which is the question Nearest Five asks.
 */
object TaxonDistance {

    /** Distance between two species with nothing above Life in common. */
    const val MAX_HOPS = 12

    /**
     * The deepest rank the two still share, as an index into [Lineage.ranks], or -1 for
     * "nothing above Life".
     *
     * A rank **both** lack is skipped rather than counted as a mismatch. GBIF's backbone
     * gives no class at all to a ray-finned fish, and stopping at the gap would put two
     * salmon 8 hops apart while they sit in the same family — which is simply wrong. A rank
     * only **one** of them lacks does stop the walk, because carrying on would mean claiming
     * a shared ancestor we were never told about.
     */
    fun sharedRankIndex(a: Lineage, b: Lineage): Int {
        var shared = -1
        val left = a.ranks
        val right = b.ranks
        for (i in left.indices) {
            val x = left[i]
            val y = right[i]
            if (x.isNullOrBlank() && y.isNullOrBlank()) continue
            if (!x.isNullOrBlank() && x == y) shared = i else break
        }
        return shared
    }

    /**
     * Hops between two species, or null when either is unclassified — a user-added species
     * has no lineage until its backfill, and inventing 12 for it would put it as far from
     * everything as a mushroom is from a goose, which is a claim rather than a measurement.
     */
    fun hops(a: Lineage, b: Lineage): Int? {
        if (!a.isKnown || !b.isKnown) return null
        return 2 * (Lineage.RANK_COUNT - sharedRankIndex(a, b))
    }

    /**
     * The [limit] species closest to [focal], nearest first.
     *
     * Ties are the normal case rather than the exception — everything in one family is the
     * same 2 hops away, and 119 of the animals are all exactly 10 from the Banana Slug — so
     * they break on the app's own class first. Without that a slug's neighbours are whichever
     * birds happen to hold the low dex numbers, and every distant species shows the same
     * list; with it a slug gets the sea star, the anemone and the urchin. Dex number settles
     * what remains, so the answer is stable between launches.
     */
    fun nearest(
        focal: SpeciesSummary,
        others: List<SpeciesSummary>,
        limit: Int,
    ): List<Neighbour> {
        if (!focal.lineage.isKnown || limit <= 0) return emptyList()
        return others.asSequence()
            .filter { it.id != focal.id }
            .mapNotNull { other ->
                val d = hops(focal.lineage, other.lineage) ?: return@mapNotNull null
                Neighbour(
                    species = other,
                    hops = d,
                    sharedRank = Lineage.rankNameAt(sharedRankIndex(focal.lineage, other.lineage)),
                    sharedTaxon = focal.lineage.ranks
                        .getOrNull(sharedRankIndex(focal.lineage, other.lineage)),
                )
            }
            .sortedWith(
                compareBy<Neighbour> { it.hops }
                    .thenBy { if (it.species.taxClass == focal.taxClass) 0 else 1 }
                    .thenBy { it.species.dexNumber },
            )
            .take(limit)
            .toList()
    }

    /**
     * How many species in [others] sit exactly [hops] away from [focal] — the number behind
     * the screen's footnote. The five shown are an arbitrary five of these, and saying so is
     * more honest than implying they were singled out.
     */
    fun countAt(focal: SpeciesSummary, others: List<SpeciesSummary>, hops: Int): Int =
        others.count {
            it.id != focal.id && hops(focal.lineage, it.lineage) == hops
        }
}

/** One row of the Nearest Five screen. */
data class Neighbour(
    val species: SpeciesSummary,
    val hops: Int,
    /** "family", "order", … or null when the two share nothing above Life. */
    val sharedRank: String?,
    /** "Sciuridae", "Mammalia", … or null in the same case. */
    val sharedTaxon: String?,
)
