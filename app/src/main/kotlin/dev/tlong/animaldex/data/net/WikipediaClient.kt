package dev.tlong.animaldex.data.net

import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject

/**
 * Wikipedia supplies the prose and the picture (DESIGN.md D10). Four requests, in the order
 * the build-time pipeline settled on:
 *
 * 1. REST `page/summary` by scientific name, falling back to the common name — this also
 *    normalises redirects, which matters because `action=parse` does **not** follow them:
 *    asking for sections of "Ixoreus naevius" returns an empty list, asking for "Varied
 *    thrush" returns nine.
 * 2. `action=parse&prop=sections` on the *normalised* title, to find the habitat section.
 * 3. `action=parse&section=N&prop=wikitext` for that section's prose.
 * 4. Commons `imageinfo&iiprop=extmetadata` for the image's licence and author (M17).
 *
 * A missing habitat section is normal (R5), not an error: the lede is the documented
 * fallback and the card marks which one it used.
 */
class WikipediaClient(private val fetcher: JsonFetcher) {

    suspend fun facts(scientificName: String?, commonName: String?): LookupResult<WikipediaFacts> {
        val titles = listOfNotNull(
            scientificName?.trim()?.takeIf { it.isNotEmpty() },
            commonName?.trim()?.takeIf { it.isNotEmpty() },
        ).distinct()
        if (titles.isEmpty()) return LookupResult.NotFound

        var lastFailure: String? = null
        for (title in titles) {
            when (val response = fetcher.get(summaryUrl(title))) {
                is FetchResult.Body -> {
                    val summary = parseWikipediaSummary(response.text) ?: continue
                    return LookupResult.Found(enrich(summary))
                }

                FetchResult.NotFound -> Unit
                is FetchResult.Failed -> lastFailure = response.reason
            }
        }
        return lastFailure?.let { LookupResult.Failed(it) } ?: LookupResult.NotFound
    }

    private suspend fun enrich(summary: WikipediaSummary): WikipediaFacts {
        val habitat = habitatFor(summary)
        val attribution = summary.imageUrl?.let { commonsAttribution(it) }
        return WikipediaFacts(
            title = summary.title,
            description = Wikitext.firstSentences(Wikitext.stripHtml(summary.extract), 2),
            habitatText = habitat.first,
            habitatSource = habitat.second,
            imageUrl = summary.imageUrl,
            imageAttribution = attribution,
            infoUrl = summary.pageUrl,
        )
    }

    /** Returns the prose and a provenance label — `wikipedia:section:…` or `wikipedia:lede`. */
    private suspend fun habitatFor(summary: WikipediaSummary): Pair<String?, String?> {
        val sections = when (val response = fetcher.get(sectionsUrl(summary.title))) {
            is FetchResult.Body -> parseWikipediaSections(response.text)
            else -> emptyList()
        }
        val section = pickHabitatSection(sections)
        if (section != null) {
            val wikitext = when (val response = fetcher.get(sectionWikitextUrl(summary.title, section.index))) {
                is FetchResult.Body -> parseWikipediaSectionWikitext(response.text)
                else -> null
            }
            val prose = Wikitext.firstSentences(Wikitext.strip(wikitext), 3)
            // Below this length the "section" is a stub or a caption, and the lede reads better.
            if (prose.length >= MIN_HABITAT_CHARS) {
                return prose to "wikipedia:section:${section.line}"
            }
        }
        val lede = Wikitext.firstSentences(Wikitext.stripHtml(summary.extract), 3)
        return lede.takeIf { it.isNotEmpty() } to lede.takeIf { it.isNotEmpty() }?.let { "wikipedia:lede" }
    }

    private suspend fun commonsAttribution(imageUrl: String): String? {
        val fileName = commonsFileName(imageUrl) ?: return null
        return when (val response = fetcher.get(commonsImageInfoUrl(fileName))) {
            is FetchResult.Body -> parseCommonsAttribution(response.text)
            else -> null
        }
    }
}

/** Shorter than this and the section is a stub; the lede is the better answer (R5). */
private const val MIN_HABITAT_CHARS = 60

/** Author lines on Commons can be a paragraph of HTML; the credit line is one line. */
private const val MAX_AUTHOR_CHARS = 80

data class WikipediaFacts(
    val title: String,
    val description: String?,
    val habitatText: String?,
    /** Which fallback produced [habitatText]; the card tells the user "Habitat · Wikipedia". */
    val habitatSource: String?,
    val imageUrl: String?,
    val imageAttribution: String?,
    val infoUrl: String?,
)

internal data class WikipediaSummary(
    val title: String,
    val extract: String?,
    val imageUrl: String?,
    val pageUrl: String?,
)

internal data class WikipediaSection(val index: String, val line: String)

private const val WIKI_API = "https://en.wikipedia.org/w/api.php"
private const val WIKI_REST = "https://en.wikipedia.org/api/rest_v1/page/summary/"
private const val COMMONS_API = "https://commons.wikimedia.org/w/api.php"

internal fun summaryUrl(title: String): String =
    WIKI_REST + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")

internal fun sectionsUrl(title: String): String =
    "$WIKI_API?action=parse&prop=sections&format=json&formatversion=2&page=" + title.enc()

internal fun sectionWikitextUrl(title: String, index: String): String =
    "$WIKI_API?action=parse&prop=wikitext&format=json&formatversion=2&section=" + index.enc() +
        "&page=" + title.enc()

internal fun commonsImageInfoUrl(fileName: String): String =
    "$COMMONS_API?action=query&prop=imageinfo&iiprop=extmetadata&format=json&formatversion=2" +
        "&titles=" + "File:$fileName".enc()

private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

internal fun parseWikipediaSummary(body: String): WikipediaSummary? {
    val root = body.asJsonObject() ?: return null
    // The REST endpoint answers a missing page with a 404 body carrying a status field.
    if (root.int("status") != null && root["title"] == null) return null
    if (root.string("type")?.contains("not_found") == true) return null
    val title = root.obj("titles")?.string("normalized") ?: root.string("title") ?: return null
    return WikipediaSummary(
        title = title,
        extract = root.string("extract"),
        imageUrl = cleanImageUrl(root.obj("originalimage")?.string("source")),
        pageUrl = root.obj("content_urls")?.obj("desktop")?.string("page"),
    )
}

/** The REST API decorates image URLs with `utm_*` tracking parameters; Coil does not need them. */
internal fun cleanImageUrl(url: String?): String? = url?.substringBefore('?')?.takeIf { it.isNotBlank() }

internal fun parseWikipediaSections(body: String): List<WikipediaSection> {
    val sections = body.asJsonObject()?.obj("parse")?.array("sections") ?: return emptyList()
    return sections.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val index = row.string("index") ?: return@mapNotNull null
        val line = row.string("line") ?: return@mapNotNull null
        WikipediaSection(index, line)
    }
}

/**
 * Section titles vary a lot (R5). "Ecology" is last deliberately: on some articles it is about
 * metabolism, while "Distribution" and "Range" are reliably about where the animal lives.
 */
internal fun pickHabitatSection(sections: List<WikipediaSection>): WikipediaSection? {
    for (wanted in listOf("habitat", "distribution", "range", "ecology")) {
        sections.firstOrNull { it.line.lowercase().contains(wanted) }?.let { return it }
    }
    return null
}

internal fun parseWikipediaSectionWikitext(body: String): String? =
    body.asJsonObject()?.obj("parse")?.string("wikitext")

/** `Wikimedia Commons · CC BY-SA 4.0 · Rhododendrites` — the credit line M17 renders. */
internal fun parseCommonsAttribution(body: String): String? {
    val page = body.asJsonObject()?.obj("query")?.array("pages")?.firstOrNull() as? JsonObject
        ?: return null
    val meta = (page.array("imageinfo")?.firstOrNull() as? JsonObject)?.obj("extmetadata")
        ?: return null

    fun value(key: String): String? =
        Wikitext.stripHtml(meta.obj(key)?.string("value")).takeIf { it.isNotBlank() }

    val license = value("LicenseShortName") ?: value("License") ?: "see Commons"
    var author = value("Artist") ?: value("Credit") ?: "unknown"
    author = author.trim(' ', '·', ',', ';')
    if (author.length > MAX_AUTHOR_CHARS) {
        author = author.substring(0, MAX_AUTHOR_CHARS - 3).trimEnd() + "…"
    }
    return "Wikimedia Commons · $license · $author"
}

/**
 * Recover the Commons `File:` name from an upload URL. Thumbnail URLs look like
 * `…/commons/thumb/a/ac/<name>.jpg/330px-<name>.jpg`, where the real name is the
 * second-to-last segment.
 */
internal fun commonsFileName(imageUrl: String): String? {
    val path = imageUrl.substringBefore('?').substringAfter("://").substringAfter('/')
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val name = if (path.contains("/thumb/") && parts.size >= 2) parts[parts.size - 2] else parts.last()
    return runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
}
