package dev.tlong.biodex.data.catalogue

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled Duke's index (ARCHITECTURE.md 11.2), read from the **shipped asset** rather than
 * from a copy of it (`RealDukeAsset` says why). Every species named below is real and every
 * lookup runs against the file the phone carries, so these fail if a regeneration changes what
 * the app would actually tell someone.
 *
 * Where a hard count would break on a legitimate recompaction the assertion is on the shape
 * instead — "found only through a synonym", "poisonous and below the medicinal threshold" —
 * because a build that fails for no reason gets ignored, and this is the dataset where being
 * ignored is expensive.
 */
class DukeIndexTest {

    private fun index() = RealDukeAsset.index()

    private fun openResource(path: String): InputStream =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing fixture $path" }

    // -----------------------------------------------------------------------
    // The corpus, in the shape 11.3 describes it.
    // -----------------------------------------------------------------------

    @Test
    fun `the shipped asset is the whole ethnobotanical table`() {
        val taxa = RealDukeAsset.taxa()

        assertTrue(index().available)
        // 13,010 taxa in the source table. A floor rather than an equality: the pipeline may
        // legitimately re-derive this, and only losing most of it is a bug.
        assertTrue("only ${taxa.size} taxa", taxa.size > 10_000)
        assertTrue(taxa.values.sumOf { it.recordCount } > 50_000)
    }

    // -----------------------------------------------------------------------
    // The lookup, and the synonym pass that R15 exists for.
    // -----------------------------------------------------------------------

    @Test
    fun `the accepted binomial is tried first`() {
        val record = index().lookup("Achillea millefolium")!!

        // Yarrow is the anchor: 105 records is the measured figure the design quotes, and it is
        // worth knowing if it ever stops being true.
        assertEquals(105, record.recordCount)
        assertTrue(record.activities.size >= DukeIndex.MEDICINAL_ACTIVITY_THRESHOLD)
        assertFalse(record.poison)
    }

    @Test
    fun `Oregon grape is found through its synonym, which is the whole point of the pass`() {
        val duke = index()

        // Duke's files it under Mahonia. The accepted GBIF name alone finds nothing, and a
        // well-known medicinal plant coming back empty is the R15 failure mode.
        assertNull(duke.lookup("Berberis aquifolium"))
        assertTrue(duke.lookup("Berberis aquifolium", listOf("Mahonia aquifolium")) != null)
    }

    @Test
    fun `the first hit wins, in the order the names were given`() {
        val record = index().lookup(
            accepted = "Nothing recorded here",
            synonyms = listOf("Achillea millefolium", "Urtica dioica"),
        )!!

        assertEquals("the earlier synonym must win", 105, record.recordCount)
    }

    @Test
    fun `no record is an ordinary state, not an error`() {
        // About a fifth of the sampled species have nothing; devil's club and evergreen
        // huckleberry are the two 11.3 names.
        assertNull(index().lookup("Oplopanax horridus"))
        assertNull(index().lookup("Vaccinium ovatum", listOf("Vaccinium ovatum saporosum")))
    }

    @Test
    fun `names are matched on genus and species only, whatever case or spacing`() {
        // GBIF hands back trinomial synonyms; Duke's rows are keyed on two words.
        assertEquals("sambucus caerulea", dukeKey("  Sambucus   caerulea velutina  "))
        assertEquals("achillea millefolium", dukeKey("ACHILLEA millefolium"))

        assertEquals(105, index().lookup("  achillea   MILLEFOLIUM  ")!!.recordCount)
    }

    // -----------------------------------------------------------------------
    // The one rule the pipeline and the app share (11.2).
    // -----------------------------------------------------------------------

    @Test
    fun `three distinct activities is the medicinal threshold, on both sides of it`() {
        val duke = index()

        // Above: yarrow and stinging nettle, both at the eight-activity cap. Below: western
        // sword fern and yerba santa, which Duke's barely records. Asserted as the rule rather
        // than as counts, so a recompaction that moves a number by one does not fail the build.
        assertTrue(DukeIndex.medicinalByRule(duke.lookup("Achillea millefolium")))
        assertTrue(DukeIndex.medicinalByRule(duke.lookup("Urtica dioica")))
        assertFalse(DukeIndex.medicinalByRule(duke.lookup("Polystichum munitum")))
        assertFalse(DukeIndex.medicinalByRule(duke.lookup("Eriodictyon californicum")))
    }

    @Test
    fun `the threshold is doing real work, with species sitting on both sides of it`() {
        val taxa = RealDukeAsset.taxa().values.filter { it.activities.isNotEmpty() }

        // If everything with a record cleared the rule, the rule would not be a rule.
        assertTrue(taxa.any { DukeIndex.medicinalByRule(it) })
        assertTrue(taxa.any { !DukeIndex.medicinalByRule(it) })
    }

    @Test
    fun `a species with no record is not medicinal, and asking does not throw`() {
        assertFalse(DukeIndex.medicinalByRule(null))
        assertFalse(DukeIndex.medicinalByRule(index().lookup("Oplopanax horridus")))
    }

    @Test
    fun `a poison record is carried apart from the activities, because it is not a use`() {
        val elder = index().lookup("Sambucus nigra")!!

        assertTrue(elder.poison)
        assertFalse("Poison must never be one of the activity names", "Poison" in elder.activities)
        assertTrue(RealDukeAsset.taxa().values.none { "Poison" in it.activities })
    }

    @Test
    fun `a poisonous species below the medicinal threshold is a real and common shape`() {
        val untaggedPoison = RealDukeAsset.taxa().values
            .filter { it.poison && !DukeIndex.medicinalByRule(it) }

        // Around 500 of the asset's taxa are poisonous and carry fewer than three activities.
        // They get no medicinal tag, so their caution is the only thing the app will ever say
        // about them — which is why a caution now outlives the tags it arrived without. A floor,
        // because the exact number is the pipeline's to change and 500-ish is the point.
        assertTrue("only ${untaggedPoison.size}", untaggedPoison.size > 100)
    }

    // -----------------------------------------------------------------------
    // Why the synonym pass is filtered on the specific epithet (see GbifClient).
    // -----------------------------------------------------------------------

    @Test
    fun `the redwood join the epithet filter exists to stop is real on both sides`() {
        val duke = index()

        // GBIF offers Chamaecyparis lawsoniana as a synonym of coast redwood. Duke's has no
        // record for the redwood and does have one for the cedar, so an unfiltered synonym pass
        // would not merely risk a wrong answer — it would produce one every time.
        assertNull(duke.lookup("Sequoia sempervirens"))
        assertTrue(duke.lookup("Chamaecyparis lawsoniana") != null)
    }

    // -----------------------------------------------------------------------
    // The asset may be absent or unreadable, and neither may take the app down.
    // -----------------------------------------------------------------------

    @Test
    fun `a missing asset is an empty index, never a crash`() {
        val duke = DukeIndex(assets = { null })

        assertFalse(duke.available)
        assertNull(duke.lookup("Achillea millefolium"))
    }

    @Test
    fun `a corrupt asset is an empty index too`() {
        val duke = DukeIndex(assets = { "not json at all".byteInputStream() })

        assertFalse(duke.available)
        assertNull(duke.lookup("Achillea millefolium"))
    }

    @Test
    fun `both asset shapes parse, so whichever the pipeline emits works`() {
        // The shipped asset carries inline activity strings under `taxa`; 11.3 also describes a
        // deduplicated string table. This one fixture stays hand-written because it tests the
        // parser's tolerance rather than the data — it is a shape the pipeline does not
        // currently emit, which is exactly why no real file can stand in for it.
        val inline = DukeIndex(assets = { openResource("catalogue/duke_fixture_inline.json") })

        assertEquals(3, inline.lookup("Achillea millefolium")!!.activities.size)
        assertEquals(listOf("Astringent", "Laxative"), inline.lookup("Mahonia aquifolium")!!.activities)
    }
}
