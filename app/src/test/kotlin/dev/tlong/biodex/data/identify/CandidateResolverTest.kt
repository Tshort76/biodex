package dev.tlong.biodex.data.identify

import dev.tlong.biodex.data.net.FetchResult
import dev.tlong.biodex.data.net.GbifClient
import dev.tlong.biodex.data.net.JsonFetcher
import dev.tlong.biodex.data.net.LookupResult
import dev.tlong.biodex.data.net.matchUrl
import dev.tlong.biodex.data.net.vernacularSearchUrl
import dev.tlong.biodex.domain.Kingdom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M32 and M33 — the rules that decide what a service's suggestion is allowed to become.
 *
 * The GBIF payloads driving this are the real captured ones wherever the captured set has the
 * name; the three constructed ones are noted in [IdentifyFixtures].
 */
class CandidateResolverTest {

    // -----------------------------------------------------------------------
    // M33. The service says one name, GBIF's accepted name is another, and the
    // catalogue carries the second — which is the whole reason validation sits
    // between the provider and the screen.
    // -----------------------------------------------------------------------

    @Test
    fun `a candidate matches the catalogue through GBIF's accepted name`() = runBlocking {
        // Pl@ntNet answers "Berberis aquifolium"; GBIF's accepted name is "Mahonia aquifolium",
        // which is what P048 carries. Matching on the provider's raw string would miss it.
        val resolver = resolverFor(
            matchUrl("Berberis aquifolium") to
                IdentifyFixtures.readNet("gbif_match_berberis_aquifolium.json"),
        )

        val result = resolver.resolve(
            listOf(IdCandidate("Berberis aquifolium", "Oregon grape", 0.72)),
            IdentifyFixtures.catalogue,
            Kingdom.PLANT,
        )

        val resolved = (result as LookupResult.Found).value.candidates.single()
        assertEquals("Mahonia aquifolium", resolved.scientificName)
        assertEquals("p048", resolved.catalogueSpeciesId)
        assertTrue(resolved.inCatalogue)
    }

    @Test
    fun `the catalogue's subspecies rows match a species-level answer`() = runBlocking {
        // The catalogue carries Roosevelt Elk as the trinomial `Cervus canadensis roosevelti`.
        // A comparison on the whole string would read the species-level answer as "not in dex"
        // and offer to add a species the user already has.
        assertEquals(
            "a072",
            catalogueMatch("Cervus canadensis", IdentifyFixtures.catalogue),
        )
    }

    @Test
    fun `matching is on scientific name, never on common name`() {
        // "Oregon Grape" is P048's common name and would match if names were compared loosely.
        assertNull(catalogueMatch("Oregon Grape", IdentifyFixtures.catalogue))
        assertEquals("p048", catalogueMatch("mahonia AQUIFOLIUM", IdentifyFixtures.catalogue))
        // A bare genus is not a species and must never match a species row.
        assertNull(catalogueMatch("Mahonia", IdentifyFixtures.catalogue))
    }

    @Test
    fun `a validated name the catalogue does not have is the add-your-own case`() = runBlocking {
        val resolver = resolverFor(
            matchUrl("Berberis nervosa") to
                IdentifyFixtures.read("gbif_match_berberis_nervosa.json"),
        )

        val result = resolver.resolve(
            listOf(IdCandidate("Berberis nervosa", "Cascade barberry", 0.14)),
            IdentifyFixtures.catalogue,
            Kingdom.PLANT,
        )

        val resolved = (result as LookupResult.Found).value.candidates.single()
        assertEquals("Mahonia nervosa", resolved.scientificName)
        assertNull(resolved.catalogueSpeciesId)
        assertFalse(resolved.inCatalogue)
    }

    // -----------------------------------------------------------------------
    // M32. Two drop rules, and the second is the one `match` alone cannot make.
    // -----------------------------------------------------------------------

    @Test
    fun `a name GBIF does not recognise is dropped and counted`() = runBlocking {
        val resolver = resolverFor(
            matchUrl("Berberis aquifolium") to
                IdentifyFixtures.readNet("gbif_match_berberis_aquifolium.json"),
            matchUrl("Plantus fakus") to IdentifyFixtures.readNet("gbif_match_varied_thrush.json"),
            vernacularSearchUrl("Plantus fakus") to
                IdentifyFixtures.readNet("gbif_search_zzznotananimal.json"),
            vernacularSearchUrl("Plantus fakus", 6) to
                IdentifyFixtures.readNet("gbif_search_zzznotaplant.json"),
        )

        val result = resolver.resolve(
            listOf(
                IdCandidate("Berberis aquifolium", score = 0.72),
                IdCandidate("Plantus fakus", score = 0.11),
            ),
            IdentifyFixtures.catalogue,
            Kingdom.PLANT,
        )

        val resolution = (result as LookupResult.Found).value
        assertEquals(1, resolution.candidates.size)
        assertEquals("the panel says how many were dropped", 1, resolution.dropped)
    }

    @Test
    fun `an invented epithet that GBIF resolves to its genus is dropped`() = runBlocking {
        // This is the rule `GbifClient.match` cannot make on its own. It runs with
        // `strict=false`, so "Berberis inventata" comes back as a HIGHERRANK match on the
        // genus *Berberis* — a `Found` result carrying a perfectly real name. Displayed, it
        // would look like a legitimate identification of something that does not exist.
        val resolver = resolverFor(
            matchUrl("Berberis inventata") to
                IdentifyFixtures.read("gbif_match_berberis_inventata.json"),
        )

        val result = resolver.resolve(
            listOf(IdCandidate("Berberis inventata", score = 0.06)),
            IdentifyFixtures.catalogue,
            Kingdom.PLANT,
        )

        assertEquals("nothing survived, so there is nothing to show", LookupResult.NotFound, result)
    }

    @Test
    fun `the higher-rank rule is the same one the Roosevelt Elk capture demonstrates`() {
        // The captured payload for "Roosevelt Elk": matchType HIGHERRANK at species rank,
        // resolving to the European red deer. Rank alone would let it through; the match kind
        // is what stops it.
        val elk = dev.tlong.biodex.data.net.parseGbifMatch(
            IdentifyFixtures.readNet("gbif_match_roosevelt_elk.json"),
        )!!.best

        assertEquals("SPECIES", elk.rank)
        assertFalse(isDisplayable(elk, Kingdom.ANIMAL))
    }

    @Test
    fun `a candidate whose GBIF kingdom disagrees is dropped`() = runBlocking {
        // A plant classifier naming a lichen or a slime mould: GBIF is the one that knows.
        val resolver = resolverFor(
            matchUrl("Ixoreus naevius") to
                IdentifyFixtures.readNet("gbif_match_ixoreus_naevius.json"),
        )

        val result = resolver.resolve(
            listOf(IdCandidate("Ixoreus naevius", score = 0.9)),
            IdentifyFixtures.catalogue,
            Kingdom.PLANT,
        )

        assertEquals(LookupResult.NotFound, result)
    }

    // -----------------------------------------------------------------------
    // 5.2's rule about a chain of calls: could-not-ask at all versus one name's
    // problem. Reporting "1 name dropped" when the phone is offline would tell
    // the user something false about their photograph.
    // -----------------------------------------------------------------------

    @Test
    fun `a failure on the first name fails the whole resolution`() = runBlocking {
        val resolver = CandidateResolver(GbifClient(JsonFetcher { FetchResult.Failed("offline") }))

        val result = resolver.resolve(
            listOf(IdCandidate("Berberis aquifolium", score = 0.72)),
            IdentifyFixtures.catalogue,
            Kingdom.PLANT,
        )

        assertEquals("offline", (result as LookupResult.Failed).reason)
    }

    @Test
    fun `a failure after something has resolved drops only that name`() = runBlocking {
        val resolver = resolverFor(
            matchUrl("Berberis aquifolium") to
                IdentifyFixtures.readNet("gbif_match_berberis_aquifolium.json"),
        )

        val result = resolver.resolve(
            listOf(
                IdCandidate("Berberis aquifolium", score = 0.72),
                // No stub, so `FakeFetcher` answers Failed.
                IdCandidate("Berberis nervosa", score = 0.14),
            ),
            IdentifyFixtures.catalogue,
            Kingdom.PLANT,
        )

        val resolution = (result as LookupResult.Found).value
        assertEquals(1, resolution.candidates.size)
        assertEquals(1, resolution.dropped)
    }

    @Test
    fun `at most five candidates are validated`() = runBlocking {
        val fetcher = CountingFetcher()

        CandidateResolver(GbifClient(fetcher)).resolve(
            (1..9).map { IdCandidate("Genus species$it") },
            IdentifyFixtures.catalogue,
            Kingdom.PLANT,
        )

        // Each name costs one `match` call plus, on a NONE, two vernacular searches. What is
        // pinned is the number of *names* walked, not the calls each one makes.
        assertEquals(5, fetcher.matchCalls)
    }

    private fun resolverFor(vararg stubs: Pair<String, String>) = CandidateResolver(
        GbifClient(
            FakeFetcher(stubs.associate { (url, body) -> url to FetchResult.Body(body) }),
        ),
    )
}

/** Mirrors `data/net`'s own fake; that one is internal to its package. */
private class FakeFetcher(private val responses: Map<String, FetchResult>) : JsonFetcher {
    override suspend fun get(url: String): FetchResult =
        responses[url] ?: FetchResult.Failed("no stub for $url")
}

/**
 * Answers every GBIF call with a body that resolves nothing, so each name is walked in full
 * and dropped rather than short-circuiting the run the way a `Failed` would.
 */
private class CountingFetcher : JsonFetcher {
    var matchCalls = 0

    override suspend fun get(url: String): FetchResult {
        if (url.startsWith("https://api.gbif.org/v1/species/match")) matchCalls++
        return FetchResult.Body("{}")
    }
}
