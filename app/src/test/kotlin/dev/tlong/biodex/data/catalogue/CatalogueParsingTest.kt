package dev.tlong.biodex.data.catalogue

import dev.tlong.biodex.domain.TaxClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asset parsing against a fixture of the exact shape ARCHITECTURE.md 3.2 specifies
 * (section 8's "asset and API JSON parsing" line). The app reads the real
 * `assets/catalogue/pacific.json`; this reads a ten-species twin from the test classpath.
 */
class CatalogueParsingTest {

    private fun fixture(): CatalogueDocument {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("catalogue/fixture-pacific.json"),
        )
        return catalogueJson.decodeFromString(stream.use { it.readBytes().decodeToString() })
    }

    @Test
    fun `header and collections parse`() {
        val doc = fixture()

        assertEquals(1, doc.catalogueVersion)
        assertEquals("pacific", doc.regionId)
        assertEquals("Pacific", doc.regionName)
        assertEquals(7, doc.ecosystems.size)
        assertEquals(10, doc.species.size)
    }

    @Test
    fun `ecosystems carry their sort order`() {
        val first = fixture().ecosystems.minByOrNull { it.sortOrder }!!
        assertEquals("coastal-rainforest", first.id)
        assertEquals("Coastal Rainforest", first.name)
        assertEquals(1, first.sortOrder)
    }

    @Test
    fun `a full species record parses every field the app imports`() {
        val owl = fixture().species.single { it.id == "western-screech-owl" }

        assertEquals(21, owl.dexNumber)
        assertEquals("Western Screech-Owl", owl.commonName)
        assertEquals("Megascops kennicottii", owl.scientificName)
        assertEquals("bird", owl.taxClass)
        assertEquals(
            listOf("oak-chaparral", "riparian-wetland", "urban-suburban"),
            owl.ecosystemIds,
        )
        assertTrue(owl.habitatText!!.isNotBlank())
        assertTrue(owl.imageAttribution!!.contains("CC BY-SA"))
        assertTrue(owl.callAttribution!!.startsWith("Xeno-canto"))
        assertEquals("sil_bird", owl.silhouetteRes)
    }

    @Test
    fun `a species with no call parses as a normal record, not an error`() {
        // M18/R4: roughly half the catalogue has no Xeno-canto recording.
        val otter = fixture().species.single { it.id == "sea-otter" }

        assertNull(otter.callUrl)
        assertNull(otter.callAttribution)
    }

    @Test
    fun `the provenance block is present in the asset and never reaches the model`() {
        // 3.2: provenance is carried for auditability but is not imported. ignoreUnknownKeys
        // is what makes that true, and it is also what lets a newer pipeline add fields.
        val raw = javaClass.classLoader!!
            .getResourceAsStream("catalogue/fixture-pacific.json")!!
            .use { it.readBytes().decodeToString() }

        assertTrue(raw.contains("\"provenance\""))
        // Parsing succeeds regardless — CatalogueSpecies has no such field.
        assertEquals(10, fixture().species.size)
    }

    @Test
    fun `every class in the fixture maps onto the app's enum`() {
        val classes = fixture().species.map { TaxClass.fromWireName(it.taxClass) }.toSet()

        assertTrue(TaxClass.BIRD in classes)
        assertTrue(TaxClass.MAMMAL in classes)
        assertTrue(TaxClass.REPTILE in classes)
        assertTrue(TaxClass.AMPHIBIAN in classes)
        assertTrue(TaxClass.FISH in classes)
        assertTrue(TaxClass.INSECT in classes)
        assertTrue(TaxClass.OTHER_INVERTEBRATE in classes)
    }

    @Test
    fun `every ecosystem tag in the fixture is declared in its ecosystems block`() {
        val doc = fixture()
        val declared = doc.ecosystems.map { it.id }.toSet()
        val used = doc.species.flatMap { it.ecosystemIds }.toSet()

        assertEquals(emptySet<String>(), used - declared)
    }

    @Test
    fun `dex numbers in the fixture are unique`() {
        val numbers = fixture().species.map { it.dexNumber }
        assertEquals(numbers.size, numbers.toSet().size)
    }
}
