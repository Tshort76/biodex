package dev.tlong.biodex.domain

import dev.tlong.biodex.domain.DexProgressMath.MembershipRow
import dev.tlong.biodex.domain.DexProgressMath.SpeciesRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** M15 and D9: the counting rules that the grid header and the Stats screen both read. */
class DexProgressMathTest {

    private fun curated(id: String, taxClass: TaxClass, caught: Boolean) =
        SpeciesRow(id, SpeciesSource.CURATED, taxClass, taxClass.kingdom, caught)

    private fun user(id: String, taxClass: TaxClass, caught: Boolean = true) =
        SpeciesRow(id, SpeciesSource.USER, taxClass, taxClass.kingdom, caught)

    private val ecosystems = listOf(
        Ecosystem("riparian-wetland", "pacific", "Riparian & Wetland", 4),
        Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
        Ecosystem("high-desert", "pacific", "High Desert & Sagebrush", 5),
    )

    @Test
    fun `the overall meter counts curated species only, with user-added as an addendum`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = listOf(
                curated("heron", TaxClass.BIRD, caught = true),
                curated("owl", TaxClass.BIRD, caught = false),
                curated("coyote", TaxClass.MAMMAL, caught = true),
                user("user-1", TaxClass.BIRD),
                user("user-2", TaxClass.BIRD),
            ),
            memberships = emptyList(),
            ecosystems = emptyList(),
        )

        assertEquals(3, progress.totalSpecies)
        assertEquals(2, progress.caughtCount)
        assertEquals(2, progress.userAddedCount)
    }

    @Test
    fun `class meters keep user-added species out of the fraction`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = listOf(
                curated("heron", TaxClass.BIRD, caught = true),
                curated("owl", TaxClass.BIRD, caught = false),
                user("user-1", TaxClass.BIRD),
            ),
            memberships = emptyList(),
            ecosystems = emptyList(),
        )

        val birds = progress.perClass.single { it.first == TaxClass.BIRD }.second
        assertEquals(Meter(caught = 1, total = 2, userAdded = 1), birds)
    }

    @Test
    fun `a class with no species at all is omitted`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = listOf(curated("heron", TaxClass.BIRD, caught = true)),
            memberships = emptyList(),
            ecosystems = emptyList(),
        )

        assertEquals(listOf(TaxClass.BIRD), progress.perClass.map { it.first })
    }

    @Test
    fun `a multi-ecosystem species counts in every one of its ecosystems`() {
        // D9: this is why ecosystem totals sum past the catalogue size.
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = listOf(
                curated("coyote", TaxClass.MAMMAL, caught = true),
                curated("heron", TaxClass.BIRD, caught = false),
            ),
            memberships = listOf(
                MembershipRow("coyote", "high-desert"),
                MembershipRow("coyote", "coastal-rainforest"),
                MembershipRow("heron", "riparian-wetland"),
            ),
            ecosystems = ecosystems,
        )

        val byId = progress.perEcosystem.associate { it.ecosystem.id to it.animals }
        assertEquals(Meter(1, 1, 0), byId.getValue("high-desert"))
        assertEquals(Meter(1, 1, 0), byId.getValue("coastal-rainforest"))
        assertEquals(Meter(0, 1, 0), byId.getValue("riparian-wetland"))
        // Two species, but three ecosystem slots filled.
        assertEquals(3, byId.values.sumOf { it.total })
    }

    @Test
    fun `a tagged user-added species shows as an ecosystem addendum, outside the fraction`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = listOf(
                curated("heron", TaxClass.BIRD, caught = true),
                curated("owl", TaxClass.BIRD, caught = false),
                user("user-1", TaxClass.BIRD),
            ),
            memberships = listOf(
                MembershipRow("heron", "riparian-wetland"),
                MembershipRow("owl", "riparian-wetland"),
                MembershipRow("user-1", "riparian-wetland"),
            ),
            ecosystems = ecosystems,
        )

        val wetland = progress.perEcosystem.single { it.ecosystem.id == "riparian-wetland" }.animals
        assertEquals(Meter(caught = 1, total = 2, userAdded = 1), wetland)
    }

    @Test
    fun `ecosystems come back in sort order, including empty ones`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = emptyList(),
            memberships = emptyList(),
            ecosystems = ecosystems,
        )

        assertEquals(
            listOf("coastal-rainforest", "riparian-wetland", "high-desert"),
            progress.perEcosystem.map { it.ecosystem.id },
        )
        assertEquals(Meter(0, 0, 0), progress.perEcosystem.first().animals)
    }

    @Test
    fun `a duplicated join row cannot inflate a meter`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = listOf(curated("coyote", TaxClass.MAMMAL, caught = true)),
            memberships = listOf(
                MembershipRow("coyote", "high-desert"),
                MembershipRow("coyote", "high-desert"),
            ),
            ecosystems = ecosystems,
        )

        val desert = progress.perEcosystem.single { it.ecosystem.id == "high-desert" }.animals
        assertEquals(Meter(1, 1, 0), desert)
    }

    @Test
    fun `a membership pointing at a deleted species is ignored`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = listOf(curated("coyote", TaxClass.MAMMAL, caught = true)),
            memberships = listOf(
                MembershipRow("coyote", "high-desert"),
                MembershipRow("ghost", "high-desert"),
            ),
            ecosystems = ecosystems,
        )

        assertEquals(
            Meter(1, 1, 0),
            progress.perEcosystem.single { it.ecosystem.id == "high-desert" }.animals,
        )
    }

    @Test
    fun `an empty dex is all zeroes rather than a divide by zero`() {
        val progress = DexProgressMath.compute("pacific", "Pacific USA", emptyList(), emptyList(), emptyList())

        assertEquals(0, progress.totalSpecies)
        assertEquals(0f, progress.animals.fraction, 0f)
        assertEquals(0f, progress.plants.fraction, 0f)
    }

    @Test
    fun `display numbers render curated animals, curated plants and user-added differently`() {
        assertEquals("#021", displayDexNumber(21, SpeciesSource.CURATED, Kingdom.ANIMAL))
        assertEquals("#120", displayDexNumber(120, SpeciesSource.CURATED, Kingdom.ANIMAL))
        assertEquals("P001", displayDexNumber(2001, SpeciesSource.CURATED, Kingdom.PLANT))
        assertEquals("P080", displayDexNumber(2080, SpeciesSource.CURATED, Kingdom.PLANT))
        assertEquals("U01", displayDexNumber(9001, SpeciesSource.USER, Kingdom.ANIMAL))
        assertEquals("U12", displayDexNumber(9012, SpeciesSource.USER, Kingdom.PLANT))
    }

    @Test
    fun `the stored number applies the kingdom's base to the asset's per-kingdom number`() {
        assertEquals(47, storedDexNumber(Kingdom.ANIMAL, 47))
        assertEquals(2047, storedDexNumber(Kingdom.PLANT, 47))
        // The round trip is what keeps the grid's order and the cell's label consistent.
        assertEquals(
            "P047",
            displayDexNumber(storedDexNumber(Kingdom.PLANT, 47), SpeciesSource.CURATED, Kingdom.PLANT),
        )
    }

    // -----------------------------------------------------------------------
    // The kingdoms are counted separately (D13). The property that matters most is the
    // first one: adding plants must leave every animal number exactly where it was.
    // -----------------------------------------------------------------------

    @Test
    fun `plants do not touch the animal meter`() {
        val animalsOnly = listOf(
            curated("heron", TaxClass.BIRD, caught = true),
            curated("owl", TaxClass.BIRD, caught = false),
            curated("coyote", TaxClass.MAMMAL, caught = true),
        )
        val memberships = listOf(
            MembershipRow("heron", "riparian-wetland"),
            MembershipRow("owl", "riparian-wetland"),
            MembershipRow("elder", "riparian-wetland"),
        )
        val before = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = animalsOnly,
            memberships = memberships,
            ecosystems = ecosystems,
        )
        val after = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = animalsOnly + listOf(
                curated("elder", TaxClass.SHRUB, caught = true),
                curated("fir", TaxClass.TREE, caught = false),
            ),
            memberships = memberships,
            ecosystems = ecosystems,
        )

        assertEquals(before.animals, after.animals)
        assertEquals(
            before.perEcosystem.map { it.animals },
            after.perEcosystem.map { it.animals },
        )
        assertEquals(Meter(1, 2, 0), after.plants)
    }

    @Test
    fun `each kingdom has its own meter and its own addenda`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = listOf(
                curated("heron", TaxClass.BIRD, caught = true),
                curated("owl", TaxClass.BIRD, caught = false),
                curated("elder", TaxClass.SHRUB, caught = true),
                curated("fir", TaxClass.TREE, caught = false),
                curated("nettle", TaxClass.HERB, caught = false),
                user("user-bird", TaxClass.BIRD),
                user("user-shrub", TaxClass.SHRUB),
            ),
            memberships = emptyList(),
            ecosystems = emptyList(),
        )

        assertEquals(Meter(caught = 1, total = 2, userAdded = 1), progress.animals)
        assertEquals(Meter(caught = 1, total = 3, userAdded = 1), progress.plants)
        // One number, both kingdoms — the "+2 of your own" line (D9).
        assertEquals(2, progress.userAddedCount)
        assertEquals(5, progress.totalSpecies)
    }

    @Test
    fun `an ecosystem row counts its plants beside its animals, never mixed in`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = listOf(
                curated("heron", TaxClass.BIRD, caught = true),
                curated("owl", TaxClass.BIRD, caught = false),
                curated("elder", TaxClass.SHRUB, caught = true),
                user("user-fern", TaxClass.FERN),
            ),
            memberships = listOf(
                MembershipRow("heron", "riparian-wetland"),
                MembershipRow("owl", "riparian-wetland"),
                MembershipRow("elder", "riparian-wetland"),
                MembershipRow("user-fern", "riparian-wetland"),
            ),
            ecosystems = ecosystems,
        )

        val wetland = progress.perEcosystem.single { it.ecosystem.id == "riparian-wetland" }
        assertEquals(Meter(caught = 1, total = 2, userAdded = 0), wetland.animals)
        assertEquals(Meter(caught = 1, total = 1, userAdded = 1), wetland.plants)
    }

    @Test
    fun `the region name comes through for the header pill`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = emptyList(),
            memberships = emptyList(),
            ecosystems = emptyList(),
        )

        assertEquals("Pacific USA", progress.regionName)
    }

    // -----------------------------------------------------------------------
    // S09's caution rule, shared by the detail screen and the confirm card.
    // -----------------------------------------------------------------------

    @Test
    fun `a note with no caution comes back whole`() {
        val (body, caution) = UsesNote.cautionSplit("Berries, late summer; cook before eating.")
        assertEquals("Berries, late summer; cook before eating.", body)
        assertEquals(null, caution)
    }

    @Test
    fun `a caution sentence is split off from the rest of the note`() {
        val (body, caution) = UsesNote.cautionSplit(
            "Berries, late summer. Caution: raw berries are toxic.",
        )
        assertEquals("Berries, late summer.", body)
        assertEquals("Caution: raw berries are toxic.", caution)
    }

    @Test
    fun `a note that is nothing but a caution has an empty body`() {
        val (body, caution) = UsesNote.cautionSplit("caution: recorded as poisonous.")
        assertEquals("", body)
        assertEquals("caution: recorded as poisonous.", caution)
    }

    @Test
    fun `the word caution mid-sentence is not a caution sentence`() {
        val note = "Leaves sting on contact, so use caution: gloves help."
        val (body, caution) = UsesNote.cautionSplit(note)
        assertEquals(note, body)
        assertEquals(null, caution)
    }

    @Test
    fun `a null or blank note splits into nothing`() {
        assertEquals("" to null, UsesNote.cautionSplit(null))
        assertEquals("" to null, UsesNote.cautionSplit("   "))
    }
}
