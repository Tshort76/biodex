package dev.tlong.biodex.data.net

import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.TaxClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                vernacularSearchUrl("zzqqxx nothing here", PLANTAE_KEY) to
                    FetchResult.Body(Fixtures.read("gbif_search_zzznotaplant.json")),
            ),
        )

        assertEquals(LookupResult.NotFound, GbifClient(fetcher).match("zzqqxx nothing here"))
    }

    // -----------------------------------------------------------------------
    // Plants (slice 12). Every payload below was captured live on 2026-09-02.
    // -----------------------------------------------------------------------

    @Test
    fun `a plant is read as a plant, not filed as an other-invertebrate`() {
        val match = parseGbifMatch(Fixtures.read("gbif_match_arbutus_menziesii.json"))!!

        assertEquals("Arbutus menziesii", match.best.scientificName)
        assertEquals(Kingdom.PLANT, match.best.kingdom)
        // Magnoliopsida means nothing to the animal class map; routing a madrone through it
        // would file it as an invertebrate, which is the fish bug in a second kingdom.
        assertEquals(TaxClass.HERB, match.best.taxClass)
    }

    @Test
    fun `a conifer defaults to tree and picks the conifer silhouette`() {
        val match = parseGbifMatch(Fixtures.read("gbif_match_pseudotsuga_menziesii.json"))!!

        assertEquals(Kingdom.PLANT, match.best.kingdom)
        assertEquals(TaxClass.TREE, match.best.taxClass)
        assertEquals("sil_tree_conifer", match.best.silhouetteResOverride)
    }

    @Test
    fun `a fern is read from its class`() {
        val match = parseGbifMatch(Fixtures.read("gbif_match_polystichum_munitum.json"))!!

        assertEquals(TaxClass.FERN, match.best.taxClass)
        assertNull("only a tree has two shapes", match.best.silhouetteResOverride)
    }

    @Test
    fun `the growth-form default leans on the one signal GBIF is reliable about`() {
        assertEquals(TaxClass.TREE, defaultPlantClass("Pinopsida", "Pinales"))
        assertEquals(TaxClass.TREE, defaultPlantClass(null, "Pinales"))
        assertEquals(TaxClass.FERN, defaultPlantClass("Polypodiopsida", "Polypodiales"))
        // R10: GBIF answers Magnoliopsida for an oak and a dandelion alike, and often nothing
        // at all, so herb is the default and the user picks the real form on the card.
        assertEquals(TaxClass.HERB, defaultPlantClass("Magnoliopsida", "Ericales"))
        assertEquals(TaxClass.HERB, defaultPlantClass(null, "Ericales"))
        assertEquals(TaxClass.HERB, defaultPlantClass(null, null))
    }

    @Test
    fun `a broadleaf tree the user picked keeps the broadleaf shape`() {
        assertEquals("sil_tree_broadleaf", plantSilhouetteFor(TaxClass.TREE, "Magnoliopsida", "Fagales"))
        assertNull(plantSilhouetteFor(TaxClass.SHRUB, "Pinopsida", "Pinales"))
    }

    @Test
    fun `a plant common name needs the Plantae search — Animalia returns nothing at all`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                matchUrl("Trailing Blackberry") to
                    FetchResult.Body(Fixtures.read("gbif_match_pacific_madrone.json")),
                vernacularSearchUrl("Trailing Blackberry") to
                    FetchResult.Body(Fixtures.read("gbif_search_trailing_blackberry_animals.json")),
                vernacularSearchUrl("Trailing Blackberry", PLANTAE_KEY) to
                    FetchResult.Body(Fixtures.read("gbif_search_trailing_blackberry_plants.json")),
            ),
        )

        val result = GbifClient(fetcher).match("Trailing Blackberry")

        val best = result.valueOrNull()!!.best
        assertEquals("Rubus hispidus", best.scientificName)
        assertEquals(Kingdom.PLANT, best.kingdom)
        // *Rubus ursinus* is in the list as an alternative — the card's job, not the API's.
        assertTrue(result.valueOrNull()!!.candidates.any { it.scientificName == "Rubus ursinus" })
        assertEquals(3, fetcher.requested.size)
    }

    @Test
    fun `an exact animal match stops before the plant search`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                matchUrl("Varied Thrush") to
                    FetchResult.Body(Fixtures.read("gbif_match_varied_thrush.json")),
                vernacularSearchUrl("Varied Thrush") to
                    FetchResult.Body(Fixtures.read("gbif_search_varied_thrush.json")),
            ),
        )

        GbifClient(fetcher).match("Varied Thrush")

        assertFalse(fetcher.requested.any { it.contains("highertaxonKey=6") })
    }

    @Test
    fun `a failed plant search degrades to the animal candidates rather than failing`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                matchUrl("Coyote") to FetchResult.Body(Fixtures.read("gbif_match_varied_thrush.json")),
                vernacularSearchUrl("Coyote") to
                    FetchResult.Body(Fixtures.read("gbif_search_coyote.json")),
                vernacularSearchUrl("Coyote", PLANTAE_KEY) to FetchResult.Failed("timeout"),
            ),
        )

        val result = GbifClient(fetcher).match("Coyote")

        assertTrue(result is LookupResult.Found)
    }

    // -----------------------------------------------------------------------
    // Synonyms — what the Duke's join needs (R15).
    // -----------------------------------------------------------------------

    @Test
    fun `synonyms come back as binomials, deduplicated`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                synonymsUrl(2882802L) to
                    FetchResult.Body(Fixtures.read("gbif_synonyms_arbutus_menziesii.json")),
            ),
        )

        val synonyms = GbifClient(fetcher).synonyms(2882802L, "Arbutus menziesii")

        // GBIF's list is mostly trinomial cultivar names; Duke's is keyed on genus and species,
        // so every name is cut to its binomial before it is offered as a join key.
        assertTrue(synonyms.all { it.split(" ").size == 2 })
        assertEquals("duplicates are dropped", synonyms.size, synonyms.distinct().size)
    }

    @Test
    fun `a synonym that changes the epithet is rejected — it is a different plant`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                synonymsUrl(2683909L) to
                    FetchResult.Body(Fixtures.read("gbif_synonyms_sequoia_sempervirens.json")),
            ),
        )

        val synonyms = GbifClient(fetcher).synonyms(2683909L, "Sequoia sempervirens")

        // Live payload, 2026-09-02: GBIF really does file Port Orford cedar under coast
        // redwood. Duke's has a record for Chamaecyparis lawsoniana and none for Sequoia
        // sempervirens, so without this filter a user-added redwood would be shown another
        // tree's traditional uses — fluent, plausible and wrong (D10).
        assertFalse(synonyms.any { it.startsWith("Chamaecyparis") })
        assertFalse(synonyms.any { it == "Sequoia religiosa" || it == "Sequoia taxifolia" })
        // The real nomenclatural synonyms, which moved genus and kept the epithet, survive.
        assertTrue("Taxodium sempervirens" in synonyms)
        assertTrue("Steinhauera sempervirens" in synonyms)
        assertTrue(synonyms.all { it.split(" ")[1].lowercase() == "sempervirens" })
    }

    @Test
    fun `the epithet filter keeps the genus move R15 is actually about`() {
        // Berberis to Mahonia is the case the synonym pass exists for, and the filter must not
        // be what breaks it: the genus moves, the epithet does not.
        val body = """{"results":[
            {"canonicalName":"Berberis aquifolium"},
            {"canonicalName":"Odostemon aquifolium"},
            {"canonicalName":"Mahonia diversifolia"}
        ]}"""

        val synonyms = parseGbifSynonyms(body, "Mahonia aquifolium")

        assertEquals(listOf("Berberis aquifolium", "Odostemon aquifolium"), synonyms)
    }

    @Test
    fun `no usage key and a failed request both degrade to no synonyms, never an error`() = runBlocking {
        val fetcher = FakeFetcher(mapOf(synonymsUrl(9L) to FetchResult.Failed("offline")))

        assertEquals(emptyList<String>(), GbifClient(fetcher).synonyms(null))
        assertEquals(emptyList<String>(), GbifClient(fetcher).synonyms(9L))
        assertEquals("no key means no request at all", 1, fetcher.requested.size)
    }

    @Test
    fun `GBIF often folds the synonym away before the app ever sees it`() {
        // Live, 2026-09-02: asking for *Berberis aquifolium* returns the accepted name
        // *Mahonia aquifolium* — the name Duke's files Oregon grape under. The synonym pass is
        // still needed for the cases GBIF has not folded, but this one it solves for us.
        val match = parseGbifMatch(Fixtures.read("gbif_match_berberis_aquifolium.json"))!!

        assertEquals("Mahonia aquifolium", match.best.scientificName)
        assertEquals(Kingdom.PLANT, match.best.kingdom)
    }

    @Test
    fun `a plant vernacular search reads a real plant`() {
        val candidates = parseGbifVernacularSearch(
            Fixtures.read("gbif_search_pacific_madrone_plants.json"),
            "Pacific Madrone",
        )

        val madrone = candidates.first()
        assertEquals("Arbutus menziesii", madrone.scientificName)
        assertEquals(Kingdom.PLANT, madrone.kingdom)
        assertEquals(MatchKind.VERNACULAR_EXACT, madrone.matchKind)
    }

    @Test
    fun `an accepted usage with no synonyms is an empty list`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                synonymsUrl(3033868L) to
                    FetchResult.Body(Fixtures.read("gbif_synonyms_mahonia_aquifolium.json")),
            ),
        )

        assertEquals(emptyList<String>(), GbifClient(fetcher).synonyms(3033868L, "Mahonia aquifolium"))
    }
}
