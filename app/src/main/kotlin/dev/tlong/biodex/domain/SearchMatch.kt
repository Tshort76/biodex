package dev.tlong.biodex.domain

import java.text.Normalizer

/**
 * The one rule behind every name search in the app (M14, D54): the grid, the Register screen
 * and anything else that filters species by what the user typed.
 *
 * Two steps. First both sides are **folded** — lower-cased, accents stripped, and everything
 * that is not a letter or a digit removed — so "red tailed", "Red-tailed" and "redtailed" are
 * the same query, and a Cyrillic-looking scientific name does not defeat a Latin keyboard.
 * Second the folded query has to appear inside the folded name, either exactly or **within a
 * few edits** of exactly: a missing letter, a wrong one, an extra one, or two swapped, up to a
 * budget that grows with the query so a short query stays strict.
 *
 * Folding drops the spaces, so a match may run across words ("redtailed") — but only one that
 * starts at a word, or stays inside the word it starts in (D82). Otherwise "orca" is found in
 * "Castor canadensis", cast·**or ca**·nadensis, and a search for the orca shows the beaver.
 *
 * Every function here is pure and runs in microseconds on a 12-character name, which is what
 * lets it sit inside a `filter` over 340 species without anyone noticing.
 */
object SearchMatch {

    /**
     * Genus and species, folded — the key two names of one species share whatever authorship
     * or subspecies rides after them ("Piranga ludoviciana Wilson, 1811" is Piranga ludoviciana).
     */
    fun binomial(scientificName: String): String =
        scientificName.trim().split(Regex("\\s+")).take(2).joinToString("") { fold(it) }

    /** Lower-case letters and digits only; accents folded to their base letter. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (ch.isLetterOrDigit()) out.append(ch.lowercaseChar())
        }
        return out.toString()
    }

    /**
     * How many edits a query of this (folded) length is allowed. Under five characters the
     * match is exact: "owl" is too short for a wrong letter to leave anything behind, and at
     * one edit it would match every name containing "ow". At five to eight one edit; at nine
     * or more, two.
     */
    fun editBudget(foldedQueryLength: Int): Int = when {
        foldedQueryLength < 5 -> 0
        foldedQueryLength < 9 -> 1
        else -> 2
    }

    /**
     * True when [query] appears in [name] after folding — exactly, or within
     * [editBudget] edits of exactly. An empty query matches everything.
     */
    fun matches(name: String, query: String): Boolean {
        val q = fold(query)
        if (q.isEmpty()) return true
        val n = fold(name)
        if (wordwiseIndexOf(name, n, q) >= 0) return true
        val budget = editBudget(q.length)
        return (budget > 0 && approximatelyContainsWordwise(name, n, q, budget)) ||
            nameWithinQuery(query, q, n, editBudget(n.length))
    }

    /**
     * D88. The other direction: the whole [name] inside a longer query, as a run of the query's
     * whole words. Lens and a pasted caption say more than the dex does — "cross orb weaver
     * spider" for the Cross Orbweaver, "mallard duck" for the Mallard — and the name the dex
     * holds is in there. Whole words, so "Mink" is not in "minke whale"; four letters at least,
     * so a three-letter name does not ride along in every query that mentions it.
     */
    fun nameWithinQuery(name: String, query: String): Boolean =
        nameWithinQuery(query, fold(query), fold(name), 0)

    /** [nameWithinQuery] within [budget] edits, the first letter held fixed so a match is anchored. */
    private fun nameWithinQuery(query: String, q: String, n: String, budget: Int): Boolean {
        if (n.length < 4 || n.length >= q.length) return false
        val bounds = (wordStarts(query) + q.length).distinct().filter { it <= q.length }
        return bounds.any { start ->
            bounds.any { end ->
                end > start && end - start <= n.length + budget && n.length <= end - start + budget &&
                    q.substring(start, end).let { run ->
                        if (budget == 0) run == n else run[0] == n[0] && approximatelyContains(run, n, budget)
                    }
            }
        }
    }

    /**
     * True when [query] appears in [name] after folding, exactly, without starting inside one
     * word and running into the next. D69's "is this name already in the dex" test.
     */
    fun containsWordwise(name: String, query: String): Boolean {
        val q = fold(query)
        return q.isEmpty() || wordwiseIndexOf(name, fold(name), q) >= 0
    }

    /**
     * The first place [q] occurs in [n] (the folded [name]) that starts at a word, or ends
     * inside the word it starts in; -1 when there is none.
     */
    private fun wordwiseIndexOf(name: String, n: String, q: String): Int {
        val starts = wordStarts(name)
        var at = n.indexOf(q)
        while (at >= 0) {
            val wordStart = starts.last { it <= at }
            val wordEnd = starts.firstOrNull { it > at } ?: n.length
            if (at == wordStart || at + q.length <= wordEnd) return at
            at = n.indexOf(q, at + 1)
        }
        return -1
    }

    /**
     * The fuzzy tier under the same rule: within one word, or starting at a word — the window
     * from a word start is just long enough to hold [q] and its edits.
     */
    private fun approximatelyContainsWordwise(name: String, n: String, q: String, budget: Int): Boolean {
        val starts = wordStarts(name).distinct().filter { it < n.length }
        val ends = starts.drop(1) + n.length
        return starts.indices.any { i ->
            approximatelyContains(n.substring(starts[i], ends[i]), q, budget) ||
                approximatelyContains(n.substring(starts[i], minOf(n.length, starts[i] + q.length + budget)), q, budget)
        }
    }

    /**
     * How well [query] matches [name], best first, or null when [matches] would say no:
     * [EXACT] the whole name; [WITHIN] the whole name inside a longer query (D88); [PREFIX] its
     * start; [WORD] the start of a later word ("tanager"
     * in "Western Tanager"); [CONTAINS] anywhere; [NEAR] only within the edit budget. The
     * Register screen sorts by it so the name typed in full leads the list (D74).
     */
    fun rank(name: String, query: String): Int? {
        val q = fold(query)
        if (q.isEmpty()) return EXACT
        val n = fold(name)
        val at = wordwiseIndexOf(name, n, q)
        return when {
            n == q -> EXACT
            nameWithinQuery(query, q, n, 0) -> WITHIN
            at == 0 -> PREFIX
            at > 0 && wordStarts(name).any { n.startsWith(q, it) } -> WORD
            at > 0 -> CONTAINS
            matches(name, query) -> NEAR
            else -> null
        }
    }

    const val EXACT = 0
    const val WITHIN = 1
    const val PREFIX = 2
    const val WORD = 3
    const val CONTAINS = 4
    const val NEAR = 5

    /** Where each word of [name] begins in its folded form. */
    private fun wordStarts(name: String): List<Int> {
        var offset = 0
        return name.split(' ', '-', '/').map { word -> offset.also { offset += fold(word).length } }
    }

    /**
     * Whether [pattern] occurs somewhere in [text] with at most [maxEdits] edits — an edit
     * being a missing, extra, or wrong character, or two adjacent characters swapped.
     *
     * Sellers' approximate-substring dynamic programme with the transposition term added: one
     * row per pattern character, the top row all zeros so a match may start anywhere, and the
     * answer is the smallest value in the last row so it may end anywhere. Runtime is
     * pattern length × text length — trivial at the sizes a species name reaches.
     */
    fun approximatelyContains(text: String, pattern: String, maxEdits: Int): Boolean {
        if (pattern.isEmpty()) return true
        if (text.isEmpty()) return pattern.length <= maxEdits
        val m = pattern.length
        val n = text.length
        // Three rows are enough: current, previous, and the one before for transpositions.
        var twoBack = IntArray(n + 1)
        var prev = IntArray(n + 1) // row 0: zero cost to start a match at any column
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                val same = pattern[i - 1] == text[j - 1]
                var best = minOf(
                    prev[j] + 1, // pattern character missing from the text
                    cur[j - 1] + 1, // extra character in the text
                    prev[j - 1] + if (same) 0 else 1,
                )
                if (i > 1 && j > 1 &&
                    pattern[i - 1] == text[j - 2] && pattern[i - 2] == text[j - 1]
                ) {
                    best = minOf(best, twoBack[j - 2] + 1)
                }
                cur[j] = best
            }
            val rotate = twoBack
            twoBack = prev
            prev = cur
            cur = rotate
        }
        return prev.min() <= maxEdits
    }
}
