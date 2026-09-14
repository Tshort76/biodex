package dev.tlong.biodex.data.net

/**
 * Every fixture under `test/resources/net/` is a **real captured response**, fetched from the
 * live API on 2026-09-01 and pretty-printed so it stays diffable — GBIF's match and vernacular
 * search, Wikipedia's summary, sections and section wikitext, and Commons' extmetadata.
 */
internal object Fixtures {

    fun read(name: String): String {
        val stream = checkNotNull(
            Fixtures::class.java.classLoader?.getResourceAsStream("net/$name"),
        ) { "missing fixture net/$name" }
        return stream.use { it.readBytes().decodeToString() }
    }
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
