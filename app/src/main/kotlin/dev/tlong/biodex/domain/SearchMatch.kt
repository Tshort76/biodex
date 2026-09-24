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
 * Every function here is pure and runs in microseconds on a 12-character name, which is what
 * lets it sit inside a `filter` over 340 species without anyone noticing.
 */
object SearchMatch {

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
        if (n.contains(q)) return true
        val budget = editBudget(q.length)
        return budget > 0 && approximatelyContains(n, q, budget)
    }

    /**
     * How well [query] matches [name], best first, or null when [matches] would say no:
     * [EXACT] the whole name; [PREFIX] its start; [WORD] the start of a later word ("tanager"
     * in "Western Tanager"); [CONTAINS] anywhere; [NEAR] only within the edit budget. The
     * Register screen sorts by it so the name typed in full leads the list (D74).
     */
    fun rank(name: String, query: String): Int? {
        val q = fold(query)
        if (q.isEmpty()) return EXACT
        val n = fold(name)
        val at = n.indexOf(q)
        return when {
            n == q -> EXACT
            at == 0 -> PREFIX
            at > 0 && wordStarts(name).any { n.startsWith(q, it) } -> WORD
            at > 0 -> CONTAINS
            matches(name, query) -> NEAR
            else -> null
        }
    }

    const val EXACT = 0
    const val PREFIX = 1
    const val WORD = 2
    const val CONTAINS = 3
    const val NEAR = 4

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
