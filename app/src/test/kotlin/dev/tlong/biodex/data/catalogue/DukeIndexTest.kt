package dev.tlong.biodex.data.catalogue

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled Duke's index (ARCHITECTURE.md 11.2). Everything here runs against
 * `test/resources/catalogue/duke_fixture.json`, a twelve-taxon **verified subset** of the
 * shipped asset — every count copied row for row, so a test that passes here describes the data
 * the phone actually has rather than a plausible invention. Devil's club and Oregon grape's
 * *Berberis* name are absent on purpose; both absences are the point of a test below.
 */
class DukeIndexTest {

    private fun index(name: String? = "duke_fixture.json") = DukeIndex(
        assets = AssetReader { path ->
            if (name == null) null else openResource("catalogue/$name").takeIf { path.isNotEmpty() }
        },
    )

    private fun openResource(path: String): InputStream =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing fixture $path" }

    // -----------------------------------------------------------------------
    // The lookup, and the synonym pass that R15 exists for.
    // -----------------------------------------------------------------------

    @Test
    fun `the accepted binomial is tried first`() {
        val record = index().lookup("Achillea millefolium")!!

        assertEquals(105, record.recordCount)
        assertEquals(8, record.activities.size)
        assertEquals("Tonic", record.activities.first())
        assertFalse(record.poison)
    }

    @Test
    fun `Oregon grape is found through its synonym, which is the whole point of the pass`() {
        val duke = index()

        // Duke's files it under Mahonia. The accepted GBIF name alone finds nothing, and a
        // well-known medicinal plant coming back empty is the R15 failure mode.
        assertNull(duke.lookup("Berberis aquifolium"))

        val record = duke.lookup("Berberis aquifolium", listOf("Mahonia aquifolium"))!!
        assertEquals(3, record.recordCount)
    }

    @Test
    fun `the first hit wins, in the order the names were given`() {
        val record = index().lookup(
            accepted = "Sambucus cerulea",
            synonyms = listOf("Arbutus menziesii", "Sambucus nigra"),
        )!!

        assertEquals("the earlier synonym must win", 5, record.recordCount)
    }

    @Test
    fun `no record is an ordinary state, not an error`() {
        assertNull(index().lookup("Oplopanax horridus"))
        assertNull(index().lookup("Vaccinium ovatum", listOf("Vaccinium ovatum var. saporosum")))
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

        // Above: yarrow, 8 activities. Exactly on it: Oregon grape, 3 — the boundary is load
        // bearing and five of the shipped 80 sit on it. Below: sword fern 2, yerba santa 1.
        assertTrue(DukeIndex.medicinalByRule(duke.lookup("Achillea millefolium")))
        assertTrue(DukeIndex.medicinalByRule(duke.lookup("Mahonia aquifolium")))
        assertFalse(DukeIndex.medicinalByRule(duke.lookup("Polystichum munitum")))
        assertFalse(DukeIndex.medicinalByRule(duke.lookup("Eriodictyon californicum")))
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
    }

    // -----------------------------------------------------------------------
    // The asset may not be in the APK yet: slice 10 generates it concurrently.
    // -----------------------------------------------------------------------

    @Test
    fun `a missing asset is an empty index, never a crash`() {
        val duke = index(name = null)

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
        // deduplicated string table. Both parse, so neither shape can break the app.
        val inline = index("duke_fixture_inline.json")

        assertEquals(3, inline.lookup("Achillea millefolium")!!.activities.size)
        assertEquals(listOf("Astringent", "Laxative"), inline.lookup("Mahonia aquifolium")!!.activities)
    }

    @Test
    fun `a poisonous species below the medicinal threshold is a real and common shape`() {
        // 516 of the asset's 13,010 taxa are poisonous and carry fewer than three activities.
        // They get no medicinal tag, so their caution is the only thing the app ever says about
        // them — which is why a caution now outlives the tags it arrived without.
        val record = index().lookup("Cercocarpus montanus")!!

        assertTrue(record.poison)
        assertFalse(DukeIndex.medicinalByRule(record))
    }
}
