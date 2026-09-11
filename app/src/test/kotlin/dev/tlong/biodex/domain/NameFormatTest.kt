package dev.tlong.biodex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **M45: a typed name comes out spelled the catalogue's way.** One table per function, each
 * row a catalogue convention the formatter has to reproduce from a careless typing of it.
 */
class NameFormatTest {

    @Test
    fun `common names follow the catalogue's capitalisation`() {
        val cases = listOf(
            "brown pelican" to "Brown Pelican",
            "BROWN PELICAN" to "Brown Pelican",
            "  brown   pelican " to "Brown Pelican",
            "red-tailed hawk" to "Red-tailed Hawk",
            "Red-Tailed Hawk" to "Red-tailed Hawk",
            "douglas-fir" to "Douglas-fir",
            "anna's hummingbird" to "Anna's Hummingbird",
            "conifer chicken of the woods" to "Conifer Chicken of the Woods",
            "chicken OF THE woods" to "Chicken of the Woods",
            "the western toad" to "The Western Toad",
            "black-and-white warbler" to "Black-and-white Warbler",
            // Inner capitals are given up on purpose: the rule stays predictable.
            "McKay's Bunting" to "Mckay's Bunting",
            "Brown Pelican" to "Brown Pelican",
            "" to "",
        )
        for ((typed, expected) in cases) {
            assertEquals("formatCommonName(\"$typed\")", expected, formatCommonName(typed))
        }
    }

    @Test
    fun `scientific names capitalise the genus only`() {
        val cases = listOf(
            "pelecanus occidentalis" to "Pelecanus occidentalis",
            "PELECANUS OCCIDENTALIS" to "Pelecanus occidentalis",
            "Pelecanus Occidentalis" to "Pelecanus occidentalis",
            "  buteo   jamaicensis " to "Buteo jamaicensis",
            "quercus garryana var. semota" to "Quercus garryana var. semota",
            "Ixoreus naevius" to "Ixoreus naevius",
        )
        for ((typed, expected) in cases) {
            assertEquals("formatScientificName(\"$typed\")", expected, formatScientificName(typed))
        }
    }

    @Test
    fun `an absent scientific name stays absent, because pending reads it`() {
        assertNull(formatScientificName(null))
        assertEquals("", formatScientificName(""))
        assertEquals("   ", formatScientificName("   "))
    }

    @Test
    fun `the sweep respells only the rows that differ, and leaves a null scientific name null (D45)`() {
        val corrections = nameCorrections(
            listOf(
                NamedSpecies("u1", "brown pelican", "pelecanus OCCIDENTALIS"),
                NamedSpecies("u2", "Bald Eagle", "Haliaeetus leucocephalus"),
                NamedSpecies("u3", "banana slug", null),
                NamedSpecies("u4", "Douglas-fir", ""),
            ),
        )
        assertEquals(
            listOf(
                NameCorrection("u1", "Brown Pelican", "Pelecanus occidentalis"),
                NameCorrection("u3", "Banana Slug", null),
            ),
            corrections,
        )
        assertEquals(emptyList<NameCorrection>(), nameCorrections(corrections.map { NamedSpecies(it.id, it.commonName, it.scientificName) }))
    }

    @Test
    fun `normalized spells both names`() {
        val fields = SpeciesFields(commonName = "brown pelican", scientificName = "pelecanus OCCIDENTALIS")
            .normalized()
        assertEquals("Brown Pelican", fields.commonName)
        assertEquals("Pelecanus occidentalis", fields.scientificName)
    }
}
