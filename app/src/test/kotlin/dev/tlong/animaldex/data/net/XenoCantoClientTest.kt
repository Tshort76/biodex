package dev.tlong.animaldex.data.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Xeno-canto. **The success fixtures here are constructed, not captured** — there is no API
 * key, so no real recordings response could be fetched (ARCHITECTURE.md 5.4). They carry the
 * exact field set the build-time pipeline reads (`q`, `file`, `url`, `id`, `lic`, `rec`), which
 * is what a key later turns into live data with no code change. `xc_missing_key.json` *is*
 * real: it is what the API answered on 2026-09-01 with an empty key, which is the app's
 * situation today.
 */
class XenoCantoClientTest {

    @Test
    fun `no key means no request and no call, cleanly`() = runBlocking {
        val fetcher = FakeFetcher(emptyMap())

        val result = XenoCantoClient(fetcher, apiKey = "").bestCall("Ixoreus naevius")

        // NotFound, not Failed: the confirm card renders "no call found" as a normal state,
        // and an empty key is the shipped configuration, not a fault (M18).
        assertEquals(LookupResult.NotFound, result)
        assertTrue("no key must cost no request", fetcher.requested.isEmpty())
    }

    @Test
    fun `quality A wins over B and C`() {
        val best = parseBestRecording(Fixtures.read("xc_recordings.json"))!!

        assertEquals("https://xeno-canto.org/222222/download", best.url)
        assertEquals(
            "Xeno-canto XC222222 · //creativecommons.org/licenses/by-nc/4.0/ · A. Recordist",
            best.attribution,
        )
    }

    @Test
    fun `an empty result set is no call`() {
        assertNull(parseBestRecording(Fixtures.read("xc_empty.json")))
    }

    @Test
    fun `a key error is not mistaken for a recording`() {
        assertNull(parseBestRecording(Fixtures.read("xc_missing_key.json")))
    }

    @Test
    fun `with a key, an empty result is NotFound and a network fault is Failed`() = runBlocking {
        val url = recordingsUrl("Ixoreus naevius", "k")

        val empty = XenoCantoClient(FakeFetcher(mapOf(url to FetchResult.Body(Fixtures.read("xc_empty.json")))), "k")
        assertEquals(LookupResult.NotFound, empty.bestCall("Ixoreus naevius"))

        val broken = XenoCantoClient(FakeFetcher(mapOf(url to FetchResult.Failed("timeout"))), "k")
        assertTrue(broken.bestCall("Ixoreus naevius") is LookupResult.Failed)
    }

    @Test
    fun `the query is the pipeline's sp form`() {
        assertTrue(
            recordingsUrl("Ixoreus naevius", "abc")
                .contains("query=sp%3A%22Ixoreus+naevius%22"),
        )
    }
}
