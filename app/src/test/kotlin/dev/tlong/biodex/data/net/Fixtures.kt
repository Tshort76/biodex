package dev.tlong.biodex.data.net

import dev.tlong.biodex.data.catalogue.DukeIndex
import dev.tlong.biodex.data.catalogue.RealDukeAsset

/**
 * Every fixture under `test/resources/net/` is a **real captured response**, fetched from the
 * live API on 2026-09-01 and pretty-printed so it stays diffable — GBIF's match and vernacular
 * search, Wikipedia's summary, sections and section wikitext, and Commons' extmetadata.
 *
 * The three Xeno-canto fixtures are the exception and are marked as such where they are used:
 * `xc_missing_key.json` is real (that is what the API answers with no key, which is the app's
 * situation today), but `xc_recordings.json` and `xc_empty.json` are **constructed** from the
 * field set the build-time pipeline reads, because there is no API key to capture a real
 * success with (ARCHITECTURE.md 5.4).
 */
internal object Fixtures {

    fun read(name: String): String {
        val stream = checkNotNull(
            Fixtures::class.java.classLoader?.getResourceAsStream("net/$name"),
        ) { "missing fixture net/$name" }
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * The **shipped** Duke's asset, not a copy of it — see `RealDukeAsset` for why the tests
     * read the real file rather than a fixture that can silently drift away from it.
     */
    fun dukeIndex(): DukeIndex = RealDukeAsset.index()
}

/** A [JsonFetcher] that answers from a fixed URL→result map; anything else is a hard failure. */
internal class FakeFetcher(
    private val responses: Map<String, FetchResult>,
) : JsonFetcher {

    val requested = mutableListOf<String>()

    override suspend fun get(url: String): FetchResult {
        requested += url
        return responses[url] ?: FetchResult.Failed("no stub for $url")
    }
}
