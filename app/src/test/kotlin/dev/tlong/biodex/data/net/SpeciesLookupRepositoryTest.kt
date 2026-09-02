package dev.tlong.biodex.data.net

import dev.tlong.biodex.domain.TaxClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition of ARCHITECTURE.md 5.2: GBIF first because it supplies the name the other
 * two are keyed by, then Wikipedia and Xeno-canto, each free to find nothing on its own.
 */
class SpeciesLookupRepositoryTest {

    private fun repository(fetcher: JsonFetcher, xcKey: String = "") = SpeciesLookupRepository(
        gbif = GbifClient(fetcher),
        wikipedia = WikipediaClient(fetcher),
        xenoCanto = XenoCantoClient(fetcher, xcKey),
    )

    private val fullStubs = mapOf(
        matchUrl("Varied Thrush") to FetchResult.Body(Fixtures.read("gbif_match_varied_thrush.json")),
        vernacularSearchUrl("Varied Thrush") to FetchResult.Body(Fixtures.read("gbif_search_varied_thrush.json")),
        summaryUrl("Ixoreus naevius") to FetchResult.Body(Fixtures.read("wiki_summary_varied_thrush.json")),
        sectionsUrl("Varied thrush") to FetchResult.Body(Fixtures.read("wiki_sections_varied_thrush.json")),
        sectionWikitextUrl("Varied thrush", "3") to
            FetchResult.Body(Fixtures.read("wiki_section_habitat_varied_thrush.json")),
        commonsImageInfoUrl("Varied_thrush_(73976).jpg") to
            FetchResult.Body(Fixtures.read("commons_imageinfo_varied_thrush.json")),
    )

    @Test
    fun `the whole Varied Thrush lookup, end to end over captured payloads`() = runBlocking {
        val outcome = repository(FakeFetcher(fullStubs)).lookup("Varied Thrush")

        val resolved = outcome as LookupOutcome.Resolved
        assertEquals("Ixoreus naevius", resolved.selected.scientificName)
        assertEquals(TaxClass.BIRD, resolved.selected.taxClass)
        assertTrue(resolved.details.fields.habitatText!!.contains("breeds in western North America"))
        assertTrue(resolved.details.fields.imageUrl!!.startsWith("https://upload.wikimedia.org/"))
        assertEquals(
            "Wikimedia Commons · CC BY-SA 4.0 · Rhododendrites",
            resolved.details.fields.imageAttribution,
        )
        // No key: no call, and that is not a failure (M18, 5.4).
        assertNull(resolved.details.fields.callUrl)
        assertFalse(resolved.details.callFailed)
    }

    @Test
    fun `Wikipedia failing leaves those fields empty rather than failing the lookup`() = runBlocking {
        val stubs = fullStubs + (summaryUrl("Ixoreus naevius") to FetchResult.Failed("timeout"))

        val resolved = repository(FakeFetcher(stubs)).lookup("Varied Thrush") as LookupOutcome.Resolved

        assertEquals("Ixoreus naevius", resolved.selected.scientificName)
        assertNull(resolved.details.fields.habitatText)
        assertNull(resolved.details.fields.imageUrl)
        assertTrue(resolved.details.articleFailed)
    }

    @Test
    fun `GBIF failing is the whole lookup failing — there is no name to key the rest by`() = runBlocking {
        val stubs = mapOf(matchUrl("Varied Thrush") to FetchResult.Failed("offline"))

        assertTrue(repository(FakeFetcher(stubs)).lookup("Varied Thrush") is LookupOutcome.Failed)
    }

    @Test
    fun `a name nothing recognises is NoMatch, which the card renders differently`() = runBlocking {
        val stubs = mapOf(
            matchUrl("zzqqxx") to FetchResult.Body(Fixtures.read("gbif_match_varied_thrush.json")),
            vernacularSearchUrl("zzqqxx") to FetchResult.Body(Fixtures.read("gbif_search_zzznotananimal.json")),
        )

        assertEquals(LookupOutcome.NoMatch, repository(FakeFetcher(stubs)).lookup("zzqqxx"))
    }

    @Test
    fun `picking a different candidate re-keys Wikipedia on that species`() = runBlocking {
        val fetcher = FakeFetcher(fullStubs)
        val other = SpeciesCandidate(
            scientificName = "Turdus migratorius",
            commonName = "American Robin",
            taxClass = TaxClass.BIRD,
            matchKind = MatchKind.VERNACULAR_OTHER,
        )

        repository(fetcher).detailsFor(other, "Varied Thrush")

        assertTrue(fetcher.requested.contains(summaryUrl("Turdus migratorius")))
        assertFalse(fetcher.requested.contains(summaryUrl("Ixoreus naevius")))
    }
}
