package dev.tlong.biodex.domain

/**
 * D103. The web address the owner typed or pasted for a species, as it will be stored and
 * opened — or null when it does not name a page. A bare `inaturalist.org/taxa/…` gains
 * `https://`; anything with another scheme, a space, or a host with no dot is refused.
 */
fun normalizedWebLink(typed: String): String? {
    val text = typed.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
    val url = when (scheme) {
        "" -> "https://$text"
        "http", "https" -> text
        else -> return null
    }
    val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val name = host.substringBefore(':')
    val port = host.substringAfter(':', missingDelimiterValue = "")
    if ('@' in host || port.any { !it.isDigit() }) return null
    val parts = name.split('.')
    return url.takeIf { parts.size >= 2 && parts.all { it.isNotEmpty() } }
}
