package dev.tlong.biodex.data.net

import dev.tlong.biodex.data.catalogue.DUKE_ATTRIBUTION
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.PlantUse
import dev.tlong.biodex.domain.TaxClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composition of ARCHITECTURE.md 5.2: GBIF first because it supplies the name Wikipedia
 * is keyed by, then Wikipedia, free to find nothing on its own.
 */
class SpeciesLookupRepositoryTest {

    private fun repository(fetcher: JsonFetcher) = SpeciesLookupRepository(
        gbif = GbifClient(fetcher),
        wikipedia = WikipediaClient(fetcher),
        duke = Fixtures.dukeIndex(),
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

    // -----------------------------------------------------------------------
    // Plants (M18, 11.4): the bundled Duke's index as a second source.
    // -----------------------------------------------------------------------

    private fun plantStubs(scientificName: String, usageKey: Long) = mapOf(
        summaryUrl(scientificName) to FetchResult.Body(Fixtures.read("wiki_summary_notfound.json")),
        synonymsUrl(usageKey) to FetchResult.Body(Fixtures.read("gbif_synonyms_mahonia_aquifolium.json")),
    )

    private fun plant(
        scientificName: String,
        usageKey: Long = 1L,
        taxClass: TaxClass = TaxClass.SHRUB,
    ) = SpeciesCandidate(
        scientificName = scientificName,
        kingdom = Kingdom.PLANT,
        taxClass = taxClass,
        usageKey = usageKey,
        matchKind = MatchKind.EXACT,
    )

    @Test
    fun `the medicinal toggle defaults on above the threshold and off below it`() = runBlocking {
        val yarrow = repository(FakeFetcher(plantStubs("Achillea millefolium", 1L)))
            .detailsFor(plant("Achillea millefolium", taxClass = TaxClass.HERB), "Yarrow")
        val swordFern = repository(FakeFetcher(plantStubs("Polystichum munitum", 2L)))
            .detailsFor(plant("Polystichum munitum", taxClass = TaxClass.FERN), "Western Sword Fern")

        // Real numbers from the shipped asset: yarrow 105 records over 8 activities, sword fern
        // 2 over 2. The three-activity rule is the whole of what separates them.
        assertEquals(setOf(PlantUse.MEDICINAL), yarrow.fields.uses)
        assertEquals(105, yarrow.fields.medicinalRecordCount)
        assertEquals(DUKE_ATTRIBUTION, yarrow.fields.usesAttribution)

        assertEquals(emptySet<PlantUse>(), swordFern.fields.uses)
        assertEquals(2, swordFern.fields.medicinalRecordCount)
        // The record is still carried: the tag is a rule applied on top of the source, never a
        // filter over it, so the card can show what Duke's has even when it tags nothing.
        assertEquals(2, swordFern.fields.medicinalActivities!!.size)
    }

    @Test
    fun `the Duke's join goes through GBIF's synonyms, which is where Oregon grape lives`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                summaryUrl("Berberis aquifolium") to
                    FetchResult.Body(Fixtures.read("wiki_summary_notfound.json")),
                synonymsUrl(3L) to FetchResult.Body(
                    """{"results":[{"canonicalName":"Mahonia aquifolium"}]}""",
                ),
            ),
        )

        val details = repository(fetcher).detailsFor(plant("Berberis aquifolium", 3L), "Oregon Grape")

        assertEquals(3, details.duke?.recordCount)
        assertTrue(details.dukeConsulted)
    }

    @Test
    fun `the Duke's join never borrows another plant's record through a lumped synonym`() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf(
                summaryUrl("Sequoia sempervirens") to
                    FetchResult.Body(Fixtures.read("wiki_summary_notfound.json")),
                synonymsUrl(2683909L) to
                    FetchResult.Body(Fixtures.read("gbif_synonyms_sequoia_sempervirens.json")),
            ),
        )

        val details = repository(fetcher)
            .detailsFor(plant("Sequoia sempervirens", 2683909L, TaxClass.TREE), "Coast Redwood")

        // Duke's has nothing for coast redwood and one record for Port Orford cedar, which
        // GBIF lists among its synonyms. "No Duke's record" is the honest answer; the other
        // tree's uses would have been a fluent, plausible lie.
        assertNull(details.duke)
        assertTrue(details.dukeConsulted)
        assertEquals(emptySet<PlantUse>(), details.fields.uses)
        assertNull(details.fields.usesAttribution)
    }

    @Test
    fun `a poison record pre-fills the caution sentence`() = runBlocking {
        val details = repository(FakeFetcher(plantStubs("Sambucus nigra", 4L)))
            .detailsFor(plant("Sambucus nigra"), "Blue Elderberry")

        assertEquals(POISON_CAUTION, details.fields.usesNote)
        assertTrue(details.duke!!.poison)
        // The caution is a source's claim about the species, and it says so (M30).
        assertTrue(details.fields.usesNote!!.startsWith("Caution:"))
    }

    @Test
    fun `no Duke's record is an ordinary state — no tag, no note, no credit`() = runBlocking {
        val details = repository(FakeFetcher(plantStubs("Oplopanax horridus", 5L)))
            .detailsFor(plant("Oplopanax horridus"), "Devil's Club")

        assertNull(details.duke)
        assertTrue(details.dukeConsulted)
        assertEquals(emptySet<PlantUse>(), details.fields.uses)
        assertNull(details.fields.usesNote)
        assertNull(details.fields.usesAttribution)
        assertEquals(0, details.fields.medicinalRecordCount)
    }

    @Test
    fun `an animal is never given uses or a Duke's column to fill`() = runBlocking {
        val resolved = repository(FakeFetcher(fullStubs)).lookup("Varied Thrush") as LookupOutcome.Resolved

        assertNull("null means 'no opinion', which is what an animal has", resolved.details.fields.uses)
        assertNull(resolved.details.fields.medicinalActivities)
        assertNull(resolved.details.fields.usesAttribution)
        assertFalse(resolved.details.dukeConsulted)
    }

    @Test
    fun `a missing Duke's asset still produces a plant, just without a tag`() = runBlocking {
        val repository = SpeciesLookupRepository(
            gbif = GbifClient(FakeFetcher(emptyMap())),
            wikipedia = WikipediaClient(FakeFetcher(plantStubs("Achillea millefolium", 1L))),
            duke = null,
        )

        val details = repository.detailsFor(plant("Achillea millefolium"), "Yarrow")

        assertEquals(Kingdom.PLANT, details.fields.kingdom)
        assertEquals(emptySet<PlantUse>(), details.fields.uses)
        assertNull(details.duke)
    }
}
