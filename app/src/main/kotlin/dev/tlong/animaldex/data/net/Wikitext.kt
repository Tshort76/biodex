package dev.tlong.animaldex.data.net

/**
 * Wikitext → readable prose, ported from `tools/catalogue/build_catalogue.py`'s
 * `strip_wikitext` (ARCHITECTURE.md 5.2 allows "a small regex pass" for v1). The pipeline
 * learned these cases against 120 real articles, so the order below is not arbitrary:
 * comments and refs go first because they contain markup that later passes would mangle,
 * `{{convert}}` is expanded before templates are dropped so "to a depth of between 10 and
 * 50 m" does not become "to a depth of between .", and file links are matched by nesting
 * depth because captions contain their own `[[links]]`.
 */
object Wikitext {

    fun strip(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = COMMENT.replace(raw, "")
        text = REF.replace(text, "")
        text = expandConvert(text)
        text = TABLE.replace(text, "")
        text = stripFileLinks(text)
        repeat(5) {
            val peeled = TEMPLATE.replace(text, "")
            if (peeled == text) return@repeat
            text = peeled
        }
        text = LINK_LABELLED.replace(text) { it.groupValues[2] }
        text = LINK_PLAIN.replace(text) { it.groupValues[1] }
        text = EXTERNAL_LABELLED.replace(text) { it.groupValues[1] }
        text = EXTERNAL_BARE.replace(text, "")
        text = TAG.replace(text, "")
        text = text.replace("'''", "").replace("''", "")
        text = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("=") && !it.startsWith("|") && !it.startsWith("!") }
            .map { LIST_MARKER.replace(it, "") }
            .joinToString(" ")
        text = text.replace("&nbsp;", " ")
        return WHITESPACE.replace(text, " ").trim()
    }

    /**
     * The first [count] sentences, trimmed back to a sentence boundary if that overruns
     * [maxChars]. The card and the detail screen both want a short paragraph, not an article.
     */
    fun firstSentences(text: String?, count: Int, maxChars: Int = 600): String {
        if (text.isNullOrBlank()) return ""
        val parts = SENTENCE_BREAK.split(text.trim())
        var out = parts.take(count).joinToString(" ").trim()
        if (out.length > maxChars) {
            val cut = out.substring(0, maxChars).substringBeforeLast(". ", "")
            out = if (cut.isNotEmpty()) "$cut." else out.substring(0, maxChars).trimEnd() + "…"
        }
        return out
    }

    /** `<a href=…>Name</a>` and entity noise out of Commons' extmetadata values. */
    fun stripHtml(value: String?): String {
        if (value.isNullOrBlank()) return ""
        var out = TAG.replace(value, " ")
        out = out.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        out = NUMERIC_ENTITY.replace(out, "")
        return WHITESPACE.replace(out, " ").trim()
    }

    private fun expandConvert(input: String): String {
        var text = input
        repeat(3) {
            val next = CONVERT.replace(text) { match ->
                val args = match.groupValues[1].split('|')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains('=') }
                when {
                    args.isEmpty() -> ""
                    // Range form: {{convert|10|to|50|m|ft}} — the separator has many spellings.
                    args.size >= 4 && !args[1].isNumeric() && args[2].isNumeric() ->
                        "${args[0]} to ${args[2]} ${args[3]}"

                    args.size >= 2 -> "${args[0]} ${args[1]}"
                    else -> args[0]
                }
            }
            if (next == text) return@repeat
            text = next
        }
        return text
    }

    private fun String.isNumeric(): Boolean = NUMERIC.matches(this)

    /**
     * `[[File:…]]`, `[[Image:…]]` and `[[Category:…]]` removed by bracket depth. A non-greedy
     * regex stops at the first `]]`, which for a caption containing a wiki link is the wrong
     * one and leaves caption debris in the prose.
     */
    private fun stripFileLinks(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val match = FILE_LINK_START.matchAt(text, i)
            if (match == null) {
                out.append(text[i])
                i++
                continue
            }
            var depth = 0
            var j = i
            while (j < text.length) {
                when {
                    text.startsWith("[[", j) -> {
                        depth++
                        j += 2
                    }

                    text.startsWith("]]", j) -> {
                        depth--
                        j += 2
                        if (depth == 0) break
                    }

                    else -> j++
                }
            }
            i = j
        }
        return out.toString()
    }

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val REF = Regex(
        "<ref[^>]*?/>|<ref[^>]*?>.*?</ref>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val TABLE = Regex("\\{\\|.*?\\|\\}", RegexOption.DOT_MATCHES_ALL)
    private val TEMPLATE = Regex("\\{\\{[^{}]*\\}\\}")
    private val TAG = Regex("<[^>]+>")
    private val FILE_LINK_START = Regex("\\[\\[(?:File|Image|Category)\\s*:", RegexOption.IGNORE_CASE)
    private val CONVERT = Regex("\\{\\{\\s*(?:convert|cvt)\\s*\\|([^{}]*)\\}\\}", RegexOption.IGNORE_CASE)
    private val LINK_LABELLED = Regex("\\[\\[([^\\]|]*)\\|([^\\]]*)\\]\\]")
    private val LINK_PLAIN = Regex("\\[\\[([^\\]]*)\\]\\]")
    private val EXTERNAL_LABELLED = Regex("\\[https?://\\S+\\s+([^\\]]*)\\]")
    private val EXTERNAL_BARE = Regex("\\[https?://\\S+\\]")
    private val LIST_MARKER = Regex("^[*#:;]+\\s*")
    private val WHITESPACE = Regex("\\s+")
    private val NUMERIC = Regex("^[\\d.,]+$")
    private val NUMERIC_ENTITY = Regex("&#\\d+;")
    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"'(])")
}
