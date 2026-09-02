package dev.tlong.biodex.data.net

import dev.tlong.biodex.domain.TaxClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GBIF parsing and candidate ranking, against real captured payloads (ARCHITECTURE.md 8:
 * "one real captured response per API, plus edge fixtures").
 */
class GbifClientTest {

    // -----------------------------------------------------------------------
    // The fish hole. Slice 2 lost eight of its ten fish to this before the rule
    // existed, and a user-added fish hits exactly the same gap.
    // -----------------------------------------------------------------------

    @Test
    fun `a chordate with no class is a fish`() {
        // Real payload: Chinook Salmon has order Salmoniformes, phylum Chordata, and no class.
        val match = parseGbifMatch(Fixtures.read("gbif_match_chinook_salmon.json"))!!

        assertEquals("Oncorhynchus tshawytscha", match.best.scientificName)
        assertEquals(TaxClass.FISH, match.best.taxClass)
    }

    @Test
    fun `the fish rule applies to the vernacular search shape too`() {
        val candidates = parseGbifVernacularSearch(
            Fixtures.read("gbif_search_chinook_salmon.json"),
            "Chinook Salmon",
        )

        val salmon = candidates.first { it.scientificName == "Oncorhynchus tshawytscha" }
        assertEquals(TaxClass.FISH, salmon.taxClass)
    }

    @Test
    fun `a class GBIF does name still maps, and an arthropod is not a fish`() {
        assertEquals(TaxClass.BIRD, taxClassFor("Aves", "Chordata"))
        assertEquals(TaxClass.MAMMAL, taxClassFor("Mammalia", "Chordata"))
        assertEquals(TaxClass.FISH, taxClassFor("Chondrichthyes", "Chordata"))
        assertEquals(TaxClass.FISH, taxClassFor(null, "Chordata"))
        assertEquals(TaxClass.OTHER_INVERTEBRATE, taxClassFor(null, "Arthropoda"))
        assertEquals(TaxClass.OTHER_INVERTEBRATE, taxClassFor("Gastropoda", "Mollusca"))
    }

    // -----------------------------------------------------------------------
    // Common names: the reason the client is two-step at all.
    // -----------------------------------------------------------------------

    @Test
    fun `the match endpoint resolves nothing for a common name`() {
        // This is the captured live response for name=Varied Thrush, and it is why the
        // vernacular fallback exists at all.
        assertNull(parseGbifMatch(Fixtures.read("gbif_match_varied_thrush.json")))
    }

    @Test
    fun `the vernacular search resolves the common name to an accepted species`() {
        val candidates = parseGbifVernacularSearch(
            Fixtures.read("gbif_search_varied_thrush.json"),
            "Varied Thrush",
        )

        assertEquals("Ixoreus naevius", candidates.first().scientificName)
        assertEquals(TaxClass.BIRD, candidates.first().taxClass)
        assertEquals(MatchKind.VERNACULAR_EXACT, candidates.first().matchKind)
        assertEquals("Varied Thrush", candidates.first().commonName)
    }

    @Test
    fun `an exact vernacular hit outranks GBIF's own relevance order`() {
        // GBIF returns "Coyote Snowfly" and "Coyote Cloudywing" above Canis latrans, because
        // its relevance is substring-based. The animal actually called "Coyote" wins here.
        val candidates = parseGbifVernacularSearch(Fixtures.read("gbif_search_coyote.json"), "Coyote")

        assertEquals("Canis latrans", candidates.first().scientificName)
        assertEquals(TaxClass.MAMMAL, candidates.first().taxClass)
    }

    @Test
    fun `an ambiguous name keeps GBIF's order and offers every candidate`() {
        val candidates = parseGbifVernacularSearch(Fixtures.read("gbif_search_sparrow.json"), "sparrow")

        // Every one of them is a candidate the user must choose between — D10's whole reason
        // for the card. The one species GBIF actually calls "sparrow" is Palaeostruthus
        // eurius, a fossil, so nothing living is promoted and it sinks below the rest.
        assertTrue(candidates.size > 1)
        assertEquals("Accipiter minullus", candidates.first().scientificName)
        assertEquals("Palaeostruthus eurius", candidates.last().scientificName)
        assertTrue(candidates.none { it.matchKind == MatchKind.VERNACULAR_EXACT })
    }

    @Test
    fun `an unknown name yields no candidates`() {
        assertTrue(
            parseGbifVernacularSearch(
                Fixtures.read("gbif_search_zzznotananimal.json"),
                "zzqqxx nothing here",
            ).isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // Honesty about the match (M19). The pipeline's Roosevelt Elk lesson.
    // -----------------------------------------------------------------------

    @Test
    fun `a match that landed on a broader taxon says so rather than claiming an exact hit`() {
        // Real payload: GBIF answers "Cervus canadensis" with matchType HIGHERRANK and
        // species "Cervus elaphus" — the European red deer, not the Roosevelt Elk asked for.
        val match = parseGbifMatch(Fixtures.read("gbif_match_roosevelt_elk.json"))!!

        assertEquals("Cervus elaphus", match.best.scientificName)
        assertEquals(MatchKind.HIGHER_RANK, match.best.matchKind)
        assertTrue(match.best.confidenceLabel.contains("check this"))
    }

    @Test
    fun `an exact scientific match is labelled as one`() {
        val match = parseGbifMatch(Fixtures.read("gbif_match_ixoreus_naevius.json"))!!

        assertEquals("Ixoreus naevius", match.best.scientificName)
        assertEquals(MatchKind.EXACT, match.best.matchKind)
        assertEquals("exact match", match.best.confidenceLabel)
    }

    @Test
    fun `alternatives never repeat the accepted name`() {
        // Chinook's alternatives are two misspellings that both resolve to the same species.
        val match = parseGbifMatch(Fixtures.read("gbif_match_chinook_salmon.json"))!!

        assertTrue(match.alternatives.none { it.scientificName == match.best.scientificName })
        assertEquals(match.candidates.size, match.candidates.distinctBy { it.scientificName }.size)
    }

    // -----------------------------------------------------------------------
    // The two-step client itself.
    // -----------------------------------------------------------------------

    @Test
    fun `the client falls back to the vernacular search when match resolves nothing`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                matchUrl("Varied Thrush") to
                    FetchResult.Body(Fixtures.read("gbif_match_varied_thrush.json")),
                vernacularSearchUrl("Varied Thrush") to
                    FetchResult.Body(Fixtures.read("gbif_search_varied_thrush.json")),
            ),
        )

        val result = GbifClient(fetcher).match("Varied Thrush")

        assertEquals("Ixoreus naevius", result.valueOrNull()?.best?.scientificName)
        assertEquals(2, fetcher.requested.size)
    }

    @Test
    fun `a resolved scientific name costs one request, not two`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                matchUrl("Ixoreus naevius") to
                    FetchResult.Body(Fixtures.read("gbif_match_ixoreus_naevius.json")),
            ),
        )

        assertNotNull(GbifClient(fetcher).match("Ixoreus naevius").valueOrNull())
        assertEquals(1, fetcher.requested.size)
    }

    @Test
    fun `no network is Failed, not NotFound`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(matchUrl("Varied Thrush") to FetchResult.Failed("offline")),
        )

        val result = GbifClient(fetcher).match("Varied Thrush")

        assertTrue(result is LookupResult.Failed)
    }

    @Test
    fun `a name nothing recognises is NotFound, not a failure`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                matchUrl("zzqqxx nothing here") to
                    FetchResult.Body(Fixtures.read("gbif_match_varied_thrush.json")),
                vernacularSearchUrl("zzqqxx nothing here") to
                    FetchResult.Body(Fixtures.read("gbif_search_zzznotananimal.json")),
            ),
        )

        assertEquals(LookupResult.NotFound, GbifClient(fetcher).match("zzqqxx nothing here"))
    }
}
