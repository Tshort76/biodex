package dev.tlong.biodex.data.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wikipedia and Commons parsing, against real captured payloads for the Varied Thrush. */
class WikipediaClientTest {

    private val summary = Fixtures.read("wiki_summary_varied_thrush.json")
    private val sections = Fixtures.read("wiki_sections_varied_thrush.json")
    private val habitat = Fixtures.read("wiki_section_habitat_varied_thrush.json")
    private val imageInfo = Fixtures.read("commons_imageinfo_varied_thrush.json")

    @Test
    fun `the summary yields the normalised title, image and page url`() {
        val parsed = parseWikipediaSummary(summary)!!

        // The article is at "Varied thrush"; "Ixoreus naevius" is a redirect. This title is
        // what the section calls must use — action=parse does not follow redirects.
        assertEquals("Varied thrush", parsed.title)
        assertTrue(parsed.imageUrl!!.startsWith("https://upload.wikimedia.org/"))
        assertFalse("tracking parameters are stripped", parsed.imageUrl!!.contains("utm_"))
        assertEquals("https://en.wikipedia.org/wiki/Varied_thrush", parsed.pageUrl)
    }

    @Test
    fun `a missing page parses to null rather than throwing`() {
        assertNull(parseWikipediaSummary(Fixtures.read("wiki_summary_notfound.json")))
    }

    @Test
    fun `the habitat section is found by title`() {
        val section = pickHabitatSection(parseWikipediaSections(sections))!!

        assertEquals("Distribution and habitat", section.line)
        assertEquals("3", section.index)
    }

    @Test
    fun `an article with no habitat section falls back to distribution`() {
        // Banana slug has Species / Description / Distribution / Ecology / … and no "Habitat".
        val section = pickHabitatSection(parseWikipediaSections(Fixtures.read("wiki_sections_no_habitat.json")))!!

        assertEquals("Distribution", section.line)
    }

    @Test
    fun `wikitext strips to prose with no markup left in it`() {
        val prose = Wikitext.firstSentences(
            Wikitext.strip(parseWikipediaSectionWikitext(habitat)),
            3,
        )

        assertTrue(prose.startsWith("The varied thrush breeds in western North America"))
        listOf("[[", "]]", "{{", "}}", "<ref", "==", "'''").forEach {
            assertFalse("markup '$it' survived: $prose", prose.contains(it))
        }
    }

    @Test
    fun `the commons credit line names the licence and the author`() {
        assertEquals(
            "Wikimedia Commons · CC BY-SA 4.0 · Rhododendrites",
            parseCommonsAttribution(imageInfo),
        )
    }

    @Test
    fun `a commons file name survives the thumbnail url shape`() {
        assertEquals(
            "Varied thrush (73976).jpg",
            commonsFileName(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ac/" +
                    "Varied_thrush_%2873976%29.jpg/330px-Varied_thrush_%2873976%29.jpg",
            )?.replace('_', ' '),
        )
        assertEquals(
            "Varied thrush (73976).jpg",
            commonsFileName(
                "https://upload.wikimedia.org/wikipedia/commons/a/ac/Varied_thrush_%2873976%29.jpg",
            )?.replace('_', ' '),
        )
    }

    @Test
    fun `the client walks summary then sections then wikitext then commons`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                summaryUrl("Ixoreus naevius") to FetchResult.Body(summary),
                sectionsUrl("Varied thrush") to FetchResult.Body(sections),
                sectionWikitextUrl("Varied thrush", "3") to FetchResult.Body(habitat),
                commonsImageInfoUrl("Varied_thrush_(73976).jpg") to FetchResult.Body(imageInfo),
            ),
        )

        val facts = WikipediaClient(fetcher).facts("Ixoreus naevius", "Varied Thrush").valueOrNull()!!

        assertEquals("wikipedia:section:Distribution and habitat", facts.habitatSource)
        assertTrue(facts.habitatText!!.contains("breeds in western North America"))
        assertTrue(facts.description!!.isNotBlank())
        assertEquals("Wikimedia Commons · CC BY-SA 4.0 · Rhododendrites", facts.imageAttribution)
    }

    @Test
    fun `no habitat section degrades to the lede rather than to nothing`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                summaryUrl("Ixoreus naevius") to FetchResult.Body(summary),
                // Sections unavailable: the fallback chain of R5 takes over.
                sectionsUrl("Varied thrush") to FetchResult.Failed("offline"),
            ),
        )

        val facts = WikipediaClient(fetcher).facts("Ixoreus naevius", null).valueOrNull()!!

        assertEquals("wikipedia:lede", facts.habitatSource)
        assertTrue(facts.habitatText!!.isNotBlank())
    }

    @Test
    fun `the scientific name is tried first and the common name is the fallback`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                summaryUrl("Nonexistent binomial") to FetchResult.NotFound,
                summaryUrl("Varied Thrush") to FetchResult.Body(summary),
                sectionsUrl("Varied thrush") to FetchResult.NotFound,
            ),
        )

        val facts = WikipediaClient(fetcher).facts("Nonexistent binomial", "Varied Thrush")

        assertEquals("Varied thrush", facts.valueOrNull()?.title)
        assertEquals(summaryUrl("Nonexistent binomial"), fetcher.requested.first())
    }

    @Test
    fun `no article at all is NotFound, and no network is Failed`() = runBlocking {
        val missing = WikipediaClient(FakeFetcher(mapOf(summaryUrl("Nothing") to FetchResult.NotFound)))
        assertEquals(LookupResult.NotFound, missing.facts("Nothing", null))

        val offline = WikipediaClient(FakeFetcher(mapOf(summaryUrl("Nothing") to FetchResult.Failed("offline"))))
        assertTrue(offline.facts("Nothing", null) is LookupResult.Failed)
    }

    @Test
    fun `convert templates keep their measurements`() {
        val prose = Wikitext.strip("Found at depths of {{convert|10|to|50|m|ft}} in {{cite|x}} kelp.")

        assertEquals("Found at depths of 10 to 50 m in kelp.", prose)
    }
}
