package dev.tlong.animaldex.domain

import dev.tlong.animaldex.domain.DexProgressMath.MembershipRow
import dev.tlong.animaldex.domain.DexProgressMath.SpeciesRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** M15 and D9: the counting rules that the grid header and the Stats screen both read. */
class DexProgressMathTest {

    private fun curated(id: String, taxClass: TaxClass, caught: Boolean) =
        SpeciesRow(id, SpeciesSource.CURATED, taxClass, caught)

    private fun user(id: String, taxClass: TaxClass, caught: Boolean = true) =
        SpeciesRow(id, SpeciesSource.USER, taxClass, caught)

    private val ecosystems = listOf(
        Ecosystem("riparian-wetland", "pacific", "Riparian & Wetland", 4),
        Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
        Ecosystem("high-desert", "pacific", "High Desert & Sagebrush", 5),
    )

    @Test
    fun `the overall meter counts curated species only, with user-added as an addendum`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
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

        val byId = progress.perEcosystem.associate { it.ecosystem.id to it.meter }
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

        val wetland = progress.perEcosystem.single { it.ecosystem.id == "riparian-wetland" }.meter
        assertEquals(Meter(caught = 1, total = 2, userAdded = 1), wetland)
    }

    @Test
    fun `ecosystems come back in sort order, including empty ones`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            species = emptyList(),
            memberships = emptyList(),
            ecosystems = ecosystems,
        )

        assertEquals(
            listOf("coastal-rainforest", "riparian-wetland", "high-desert"),
            progress.perEcosystem.map { it.ecosystem.id },
        )
        assertEquals(Meter(0, 0, 0), progress.perEcosystem.first().meter)
    }

    @Test
    fun `a duplicated join row cannot inflate a meter`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            species = listOf(curated("coyote", TaxClass.MAMMAL, caught = true)),
            memberships = listOf(
                MembershipRow("coyote", "high-desert"),
                MembershipRow("coyote", "high-desert"),
            ),
            ecosystems = ecosystems,
        )

        val desert = progress.perEcosystem.single { it.ecosystem.id == "high-desert" }.meter
        assertEquals(Meter(1, 1, 0), desert)
    }

    @Test
    fun `a membership pointing at a deleted species is ignored`() {
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            species = listOf(curated("coyote", TaxClass.MAMMAL, caught = true)),
            memberships = listOf(
                MembershipRow("coyote", "high-desert"),
                MembershipRow("ghost", "high-desert"),
            ),
            ecosystems = ecosystems,
        )

        assertEquals(
            Meter(1, 1, 0),
            progress.perEcosystem.single { it.ecosystem.id == "high-desert" }.meter,
        )
    }

    @Test
    fun `an empty dex is all zeroes rather than a divide by zero`() {
        val progress = DexProgressMath.compute("pacific", emptyList(), emptyList(), emptyList())

        assertEquals(0, progress.totalSpecies)
        assertEquals(0f, progress.overall.fraction, 0f)
    }

    @Test
    fun `display numbers render curated and user-added species differently`() {
        assertEquals("#021", displayDexNumber(21, SpeciesSource.CURATED))
        assertEquals("#120", displayDexNumber(120, SpeciesSource.CURATED))
        assertEquals("U01", displayDexNumber(1001, SpeciesSource.USER))
        assertEquals("U12", displayDexNumber(1012, SpeciesSource.USER))
    }
}
