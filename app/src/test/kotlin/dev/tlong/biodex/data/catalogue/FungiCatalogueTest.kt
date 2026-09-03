package dev.tlong.biodex.data.catalogue

import dev.tlong.biodex.domain.FUNGUS_DEX_NUMBER_BASE
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.domain.UsesNote
import dev.tlong.biodex.domain.displayDexNumber
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fungi half of the shipped catalogue, asserted against the **real asset** rather than a
 * fixture (see [RealCatalogueAsset] for why).
 *
 * The rules under test are the ones the build machinery cannot be the last word on. The
 * pipeline enforces them at generation time, but the asset is a committed file that a person
 * can edit, and the one it would hurt to get wrong is the one with no dataset behind it:
 * Dr. Duke's is ethnobotanical and has no fungal taxa, so nothing sourced decides which
 * mushroom carries a warning or whether one carries a use tag (DESIGN-identification.md
 * 8.1 / M35 / R20).
 */
class FungiCatalogueTest {

    private val fungi = RealCatalogueAsset.speciesOf("fungus")

    /**
     * The pipeline's `EDIBILITY_RE`, in the app's words. Two parts of it are deliberate:
     * `\b` before "edible" leaves "inedible" alone, a claim in the safe direction; and
     * "eaten" counts only when it is not "eaten by", so "widely eaten" is a claim while
     * "may be eaten by caterpillars of the fungus moth" is ecology and stays.
     */
    private val edibility = Regex(
        """\b(edible|edibility|choice|delicious|tasty|palatable|good eating|safe to eat""" +
            """|delicac(y|ies)|prized|culinary|for the table)\b""" +
            """|\beaten\b(?!\s+by\b)""",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun `the asset ships thirty fungi across the three growth forms`() {
        assertEquals(30, fungi.size)
        assertEquals(
            mapOf("mushroom" to 18, "bracket" to 6, "other_fungus" to 6),
            fungi.groupingBy { it.taxClass }.eachCount(),
        )
        assertEquals((1..30).toList(), fungi.map { it.dexNumber }.sorted())
    }

    @Test
    fun `no fungus carries a use tag, a note beyond its caution, or a Duke's field`() {
        fungi.forEach { row ->
            assertEquals("${row.id} carries a use tag", emptyList<String>(), row.uses)
            assertEquals(emptyList<String>(), row.medicinalActivities)
            assertEquals(0, row.medicinalRecordCount)
            assertEquals(null, row.usesAttribution)
            // Most fungi carry no note at all now — the same as an animal. A note is
            // written only when the species itself is dangerous, and when there is one it
            // is the caution alone: a fungus has no use for the body of a note to describe,
            // and the app would drop it on import (`keptUsesNote`).
            if (row.usesNote != null) {
                val (body, caution) = UsesNote.cautionSplit(row.usesNote)
                assertEquals("${row.id} has prose outside its caution", "", body)
                assertNotNull("${row.id} has no Caution: sentence", caution)
            }
        }
    }

    @Test
    fun `nothing on a fungal record claims edibility`() {
        // Not just the curated caution: the Wikipedia lede the pipeline fetches routinely
        // opens "... is an edible mushroom", and the app makes no edibility claim about a
        // fungus anywhere (M35). Six such sentences were dropped on the first full run.
        fungi.forEach { row ->
            listOf(row.usesNote, row.description, row.habitatText).forEach { text ->
                val hit = edibility.find(text.orEmpty())
                assertEquals("${row.id}: '${hit?.value}' in \"$text\"", null, hit)
            }
        }
    }

    @Test
    fun `every fungus has the silhouette its growth form names`() {
        // The pipeline writes `sil_<taxClass>` and `Silhouettes.byClass` maps the same three
        // classes onto the same three drawables; a fungus reaching the class fallback would
        // draw an invertebrate.
        fungi.forEach { row ->
            assertEquals(row.id, "sil_${row.taxClass}", row.silhouetteRes)
        }
    }

    @Test
    fun `the importer files fungal rows under the fungus kingdom and the F block`() = runBlocking {
        val store = FakeCatalogueStore()

        val outcome = CatalogueImporter(RealCatalogueAsset.reader(), store).import()

        assertTrue(outcome is ImportOutcome.Imported)
        val imported = store.species.values.filter { it.kingdom == Kingdom.FUNGUS }
        assertEquals(30, imported.size)
        assertTrue(imported.all { it.taxClass.kingdom == Kingdom.FUNGUS })
        assertTrue(imported.all { it.source == SpeciesSource.CURATED })
        assertTrue(imported.all { it.uses.isEmpty() })
        // 11.1: the asset numbers each kingdom from 1 and the importer applies the base, so
        // the fungi occupy 4001..4030 and cannot collide with the plants at 2001..2080.
        assertEquals(
            (FUNGUS_DEX_NUMBER_BASE + 1..FUNGUS_DEX_NUMBER_BASE + 30).toList(),
            imported.map { it.dexNumber }.sorted(),
        )
        val chanterelle = store.species.getValue("pacific-golden-chanterelle")
        assertEquals(TaxClass.MUSHROOM, chanterelle.taxClass)
        assertEquals(
            "F001",
            displayDexNumber(chanterelle.dexNumber, chanterelle.source, chanterelle.kingdom),
        )
        assertEquals(
            "F030",
            displayDexNumber(
                FUNGUS_DEX_NUMBER_BASE + 30,
                SpeciesSource.CURATED,
                Kingdom.FUNGUS,
            ),
        )
    }

    @Test
    fun `the three kingdoms stay in their own dex-number blocks`() {
        val stored = store()
        val animals = stored.filter { it.kingdom == Kingdom.ANIMAL }.map { it.dexNumber }
        val plants = stored.filter { it.kingdom == Kingdom.PLANT }.map { it.dexNumber }
        val fungal = stored.filter { it.kingdom == Kingdom.FUNGUS }.map { it.dexNumber }

        assertTrue(animals.max() < plants.min())
        assertTrue(plants.max() < fungal.min())
        // The unique (regionId, dexNumber) index is what would fail the whole import if two
        // kingdoms ever overlapped, so the gap is the assertion, not the exact bases.
        assertEquals(230, stored.map { it.dexNumber }.distinct().size)
    }

    private fun store() = runBlocking {
        FakeCatalogueStore().also { CatalogueImporter(RealCatalogueAsset.reader(), it).import() }
            .species.values.toList()
    }
}
