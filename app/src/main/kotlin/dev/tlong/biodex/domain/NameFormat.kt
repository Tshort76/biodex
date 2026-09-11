package dev.tlong.biodex.domain

/**
 * How a user-typed name is spelled before it is shown or stored (M45, D43). The catalogue has
 * one convention — "Red-tailed Hawk", "Anna's Hummingbird", "Conifer Chicken of the Woods" —
 * and a species the user adds should sit in the same list without looking typed. The rules
 * are deliberately small and predictable, and they lose inner capitals on purpose: "McKay's"
 * comes out "Mckay's", because a formatter that guesses at exceptions is one nobody can
 * predict on the card.
 *
 * - Whitespace is trimmed and runs collapse to one space.
 * - Every space-separated word starts with a capital and continues in lowercase.
 * - After a hyphen the word continues in lowercase ("Double-crested", "Douglas-fir").
 * - The short joining words in [SMALL_WORDS] stay lowercase unless they lead the name.
 */
fun formatCommonName(raw: String): String {
    val words = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    return words.mapIndexed { index, word ->
        val lower = word.lowercase()
        if (index > 0 && lower in SMALL_WORDS) lower else lower.replaceFirstChar { it.titlecase() }
    }.joinToString(" ")
}

/**
 * A scientific name is "Genus species", with anything after that — a subspecies, a `var.`,
 * an author — left lowercase. Only the genus takes a capital. Null and blank stay as they
 * are, because "no scientific name" is a state the lookup lifecycle reads (M20).
 */
fun formatScientificName(raw: String?): String? {
    val words = raw?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() }.orEmpty()
    if (words.isEmpty()) return raw
    return words.mapIndexed { index, word ->
        val lower = word.lowercase()
        if (index == 0) lower.replaceFirstChar { it.titlecase() } else lower
    }.joinToString(" ")
}

/** A stored user-added species' names, as [nameCorrections] reads them. */
data class NamedSpecies(val id: String, val commonName: String, val scientificName: String?)

/** One row whose stored spelling differs from what the door would have written. */
data class NameCorrection(val id: String, val commonName: String, val scientificName: String?)

/**
 * D45: the species added before M45 existed, spelled the way the door spells them now. Only
 * rows that would actually change come back, so a sweep that finds nothing writes nothing —
 * which is what lets it run on every start without a flag to remember it by.
 */
fun nameCorrections(species: List<NamedSpecies>): List<NameCorrection> =
    species.mapNotNull { row ->
        val common = formatCommonName(row.commonName)
        val scientific = formatScientificName(row.scientificName)
        if (common == row.commonName && scientific == row.scientificName) null
        else NameCorrection(row.id, common, scientific)
    }

private val WHITESPACE = Regex("\\s+")

/** Joining words the catalogue keeps lowercase mid-name ("Chicken of the Woods"). */
private val SMALL_WORDS = setOf("of", "the", "and", "or", "in", "on", "a", "an", "at", "to", "for", "with")
