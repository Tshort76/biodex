package dev.tlong.biodex.data.identify

import dev.tlong.biodex.data.net.FetchResult
import dev.tlong.biodex.data.net.LookupResult
import dev.tlong.biodex.domain.Kingdom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pl@ntNet client, driven through its transport seam. Everything asserted here is the
 * mapping from a response to one of `LookupResult`'s three outcomes, which is what M38's
 * "candidates / no candidates / could-not-ask" rendering rests on.
 */
class PlantNetIdentifierTest {

    private fun identifier(result: FetchResult, key: String? = "test-key") =
        PlantNetIdentifier(FakeTransport(result)) { key }

    @Test
    fun `a result list parses to candidates with the name, common name and score`() = runBlocking {
        val body = FetchResult.Body(IdentifyFixtures.read("plantnet_oregon_grape.json"))

        val result = identifier(body).identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

        val candidates = (result as LookupResult.Found).value
        assertEquals(3, candidates.size)
        assertEquals("Berberis aquifolium", candidates[0].scientificName)
        assertEquals("Oregon grape", candidates[0].commonName)
        assertEquals(0.72434, candidates[0].score!!, 1e-6)
        assertEquals("Berberis nervosa", candidates[1].scientificName)
    }

    @Test
    fun `the name is taken without its author string`() = runBlocking {
        // `scientificName` in the payload is "Berberis aquifolium Pursh". GBIF's match endpoint
        // and the catalogue's own names are both author-free, so taking the authored string
        // would break both comparisons downstream.
        val body = FetchResult.Body(IdentifyFixtures.read("plantnet_oregon_grape.json"))

        val result = identifier(body).identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

        val first = (result as LookupResult.Found).value.first()
        assertFalse(first.scientificName.contains("Pursh"))
    }

    @Test
    fun `a candidate with no common names carries a null one rather than an empty string`() =
        runBlocking {
            val body = FetchResult.Body(IdentifyFixtures.read("plantnet_oregon_grape.json"))

            val result = identifier(body).identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

            assertNull((result as LookupResult.Found).value[2].commonName)
        }

    // -----------------------------------------------------------------------
    // M38's three outcomes. The distinction between the first two is the point:
    // a photo Pl@ntNet recognises nothing in is an ordinary answer about the
    // world, not an error, and must not be rendered as one.
    // -----------------------------------------------------------------------

    @Test
    fun `an empty result list is NotFound, not a failure`() = runBlocking {
        val body = FetchResult.Body(IdentifyFixtures.read("plantnet_empty_results.json"))

        val result = identifier(body).identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

        assertEquals(LookupResult.NotFound, result)
    }

    @Test
    fun `a 404 from the service is NotFound too`() = runBlocking {
        // Pl@ntNet answers a photo with no recognisable plant with a 404 and a
        // `Species not found` body rather than a 200 and an empty list.
        val result = identifier(FetchResult.NotFound)
            .identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

        assertEquals(LookupResult.NotFound, result)
    }

    @Test
    fun `quota exhaustion fails with a reason that names the quota`() = runBlocking {
        val quota = classifyIdentifyResponse(429) {
            IdentifyFixtures.read("plantnet_quota_exhausted.json")
        }

        val result = identifier(quota as FetchResult.Failed)
            .identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

        val reason = (result as LookupResult.Failed).reason
        assertTrue(reason, reason.contains("quota"))
    }

    @Test
    fun `an unparseable body fails rather than reporting no candidates`() = runBlocking {
        val result = identifier(FetchResult.Body("<html>502 Bad Gateway</html>"))
            .identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

        assertTrue(result is LookupResult.Failed)
    }

    // -----------------------------------------------------------------------
    // M39 / D24: the key is a runtime value, and its absence is a stated reason
    // rather than an empty request the service would reject anyway.
    // -----------------------------------------------------------------------

    @Test
    fun `no key fails with the sentence Settings tells the user to act on`() = runBlocking {
        val transport = FakeTransport(FetchResult.Body("{}"))

        val result = PlantNetIdentifier(transport) { null }
            .identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

        assertEquals(PlantNetIdentifier.NO_KEY_REASON, (result as LookupResult.Failed).reason)
        assertEquals("nothing is uploaded without a key", 0, transport.calls)
    }

    @Test
    fun `a blank key counts as no key`() = runBlocking {
        val transport = FakeTransport(FetchResult.Body("{}"))

        val result = PlantNetIdentifier(transport) { "   " }
            .identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

        assertTrue(result is LookupResult.Failed)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `the key travels in the URL and the photo bytes in the body`() = runBlocking {
        val transport = FakeTransport(
            FetchResult.Body(IdentifyFixtures.read("plantnet_oregon_grape.json")),
        )

        PlantNetIdentifier(transport) { "secret-key" }
            .identify(IdentifyFixtures.someBytes, Kingdom.PLANT)

        assertTrue(transport.lastUrl!!.contains("api-key=secret-key"))
        assertTrue(transport.lastUrl!!.startsWith("https://my-api.plantnet.org/v2/identify/"))
        assertEquals(IdentifyFixtures.someBytes, transport.lastImage)
    }

    @Test
    fun `it refuses a kingdom it does not identify rather than uploading anyway`() = runBlocking {
        // The registry is what decides who is called (D19); this is the belt to that braces.
        val transport = FakeTransport(FetchResult.Body("{}"))

        val result = PlantNetIdentifier(transport) { "k" }
            .identify(IdentifyFixtures.someBytes, Kingdom.FUNGUS)

        assertTrue(result is LookupResult.Failed)
        assertEquals(0, transport.calls)
    }

    // -----------------------------------------------------------------------
    // The status-code mapping. Each of these is a different sentence under the
    // button, and telling them apart is what M38 asks for.
    // -----------------------------------------------------------------------

    @Test
    fun `each status code the service uses maps to its own outcome`() {
        assertEquals(FetchResult.NotFound, classifyIdentifyResponse(404) { "{}" })
        assertTrue(classifyIdentifyResponse(200) { "{}" } is FetchResult.Body)

        val rejectedKey = classifyIdentifyResponse(401) { "{}" } as FetchResult.Failed
        assertTrue(rejectedKey.reason, rejectedKey.reason.contains("key"))

        val quota = classifyIdentifyResponse(429) { "{}" } as FetchResult.Failed
        assertTrue(quota.reason, quota.reason.contains("quota"))

        assertTrue(classifyIdentifyResponse(503) { "{}" } is FetchResult.Failed)
        assertTrue(classifyIdentifyResponse(413) { "{}" } is FetchResult.Failed)
    }

    @Test
    fun `the registry is what decides which kingdoms can be identified`() {
        val registry = IdentifierRegistry(mapOf(Kingdom.PLANT to identifier(FetchResult.NotFound)))

        assertTrue(registry.supports(Kingdom.PLANT))
        // D19: the absence of these two entries is what hides the Identify button for them.
        assertFalse(registry.supports(Kingdom.ANIMAL))
        assertFalse(registry.supports(Kingdom.FUNGUS))
        assertNull(registry[Kingdom.ANIMAL])
        assertEquals(ScoreKind.CALIBRATED, registry[Kingdom.PLANT]!!.scoreKind)
    }
}
