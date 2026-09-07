package dev.tlong.biodex.ui.nav

/**
 * What another app handed us through the share sheet (M45, D39).
 *
 * The point of this is the loop it removes. Identifying an animal happens somewhere else —
 * Merlin, Lens, a smart feeder's own app — and until now the only way back into BioDex was to
 * remember the name, open the app, and type it. A share target makes that one tap: the photo
 * arrives attached, the name arrives in the search box, and Register opens with both.
 */
data class ShareIntake(
    /** A `content://` URI from the sending app, valid only while this task holds it (D39). */
    val photoUri: String? = null,
    /** A name to search for, already cleaned by [shareIntakeFrom]. */
    val query: String? = null,
) {
    val hasSomething: Boolean get() = photoUri != null || !query.isNullOrBlank()
}

/**
 * The parsing rule, kept pure so the JVM suite can hold every shape of share a real app sends.
 *
 * Only `ACTION_SEND` counts. Some senders include both a photo and text, and both are kept:
 * the photo attaches and the text seeds the search, which is the best case and the reason
 * this returns a pair rather than one or the other.
 */
fun shareIntakeFrom(
    action: String?,
    streamUri: String?,
    text: String?,
): ShareIntake? {
    if (action != ACTION_SEND) return null
    val intake = ShareIntake(photoUri = streamUri?.takeIf { it.isNotBlank() }, query = speciesQueryFrom(text))
    return intake.takeIf { it.hasSomething }
}

/**
 * Shared text, reduced to something worth putting in a search box — or nothing.
 *
 * **Most shared text is not a species name**, and guessing wrong is worse than ignoring it: a
 * search box seeded with a paragraph finds nothing and has to be cleared by hand, which is
 * more work than the empty box it replaced. So the rule is deliberately narrow and refuses
 * anything it cannot recognise:
 *
 * - the first non-blank line only, because a share that carries a title and a link puts the
 *   name first and the noise after;
 * - never a URL, which is what "share" from a browser or a photo host mostly means;
 * - never longer than [MAX_QUERY], because a species name is two or three words and anything
 *   longer is a sentence about one.
 *
 * Surrounding quotes go because copying a name out of a page often brings them along.
 */
private fun speciesQueryFrom(text: String?): String? {
    val firstLine = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
    val unquoted = firstLine.trim('"', '\'', '“', '”', '‘', '’').trim()
    return when {
        unquoted.isBlank() -> null
        unquoted.length > MAX_QUERY -> null
        LOOKS_LIKE_A_URL.containsMatchIn(unquoted) -> null
        else -> unquoted
    }
}

private const val ACTION_SEND = "android.intent.action.SEND"

/** Long enough for "Great Basin Bristlecone Pine", short enough to exclude a sentence. */
private const val MAX_QUERY = 60

private val LOOKS_LIKE_A_URL = Regex("""^\s*\w+://|^\s*www\.""", RegexOption.IGNORE_CASE)
