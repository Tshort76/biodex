package dev.tlong.biodex.data.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D48's tag, asserted against the **real asset** (see [RealCatalogueAsset]).
 *
 * The tag is editorial — no dataset decides it, the way Duke's decides the medicinal half —
 * so what is testable is the shape of the claim rather than the claim itself: that the set is
 * the one the owner agreed to, that no animal picked up the medicinal tag along the way, and
 * that a tagged animal carries no curated note, since a season and a bag limit are a
 * regulator's answer and would be stale the year after they were written.
 */
class AnimalFoodSourceTest {

    private val animals = RealCatalogueAsset.speciesOf("animal")

    /** The list the owner settled on: a licensed season plus commonly eaten (D48, D50). */
    private val expected = setOf(
        "American Black Bear", "American Coot", "Band-tailed Pigeon", "Barred Surfperch",
        "Black Rockfish", "Black-tailed Jackrabbit", "Blue Rockfish", "Bufflehead", "Cabezon",
        "California Grunion", "California Mussel", "California Quail", "Canada Goose",
        "Chinook Salmon", "Chum Salmon", "Coho Salmon", "Cutthroat Trout", "Douglas Squirrel",
        "Dungeness Crab", "Field Cricket", "Giant Pacific Octopus", "Gooseneck Barnacle",
        "Kelp Greenling", "Largemouth Bass", "Lingcod", "Mallard", "Mourning Dove",
        "Mule Deer", "Opaleye", "Pacific Herring", "Pacific Oyster", "Pacific Razor Clam",
        "Pink Salmon", "Purple Sea Urchin", "Rainbow Trout", "Red Rock Crab", "Roosevelt Elk",
        "Sandhill Crane", "Snowshoe Hare", "Sockeye Salmon", "Starry Flounder",
        "Ten-lined June Beetle", "Valley Carpenter Bee", "Western Gray Squirrel",
        "Western Honey Bee", "White Sturgeon", "Wild Turkey", "Wood Duck",
        "Yellow-faced Bumble Bee",
    )

    @Test
    fun `exactly the agreed animals carry the food-source tag (D48)`() {
        val tagged = animals.filter { "edible" in it.uses }.map { it.commonName }.toSet()
        assertEquals(expected, tagged)
    }

    @Test
    fun `the medicinal tag stays plant-only, because Duke's is a plant database`() {
        val medicinal = animals.filter { "medicinal" in it.uses }.map { it.commonName }
        assertEquals(emptyList<String>(), medicinal)
        assertTrue(animals.all { it.medicinalRecordCount == 0 })
    }

    @Test
    fun `a tagged animal carries the tag and nothing else - no note, no Duke's credit`() {
        for (animal in animals.filter { "edible" in it.uses }) {
            assertEquals(animal.commonName, "animal", animal.kingdom)
            assertEquals(animal.commonName, null, animal.usesNote)
            assertEquals(animal.commonName, null, animal.usesAttribution)
        }
    }

    @Test
    fun `an untagged animal is left exactly as it was`() {
        for (animal in animals.filter { "edible" !in it.uses }) {
            assertTrue(animal.commonName, animal.uses.isEmpty())
            assertEquals(animal.commonName, null, animal.usesAttribution)
        }
    }
}
