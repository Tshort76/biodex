package dev.tlong.biodex.ui.stats

import dev.tlong.biodex.domain.DexProgressMath
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Meter
import dev.tlong.biodex.domain.SpeciesSource
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M15/S08. The phone check for this slice is "the stats reconcile with the grid by
 * hand-count", so what is worth pinning here is exactly what a hand-count would catch: the
 * fraction counting curated species only, the user-added addendum sitting outside it, and a
 * strip that lists species rather than photographs.
 */
class StatsStateTest {

    private fun summary(
        id: String,
        source: SpeciesSource = SpeciesSource.CURATED,
        taxClass: TaxClass = TaxClass.BIRD,
        caughtAt: Long? = null,
        captureCount: Int = if (caughtAt == null) 0 else 1,
        ecosystemIds: List<String> = listOf("coastal-rainforest"),
    ) = SpeciesSummary(
        id = id,
        regionId = "pacific",
        dexNumber = 1,
        source = source,
        detailsPending = false,
        commonName = id,
        scientificName = null,
        taxClass = taxClass,
        kingdom = taxClass.kingdom,
        silhouetteRes = "sil_bird",
        ecosystemIds = ecosystemIds,
        caughtAt = caughtAt,
        thumbPath = if (caughtAt == null) null else "thumbnails/$id.jpg",
        captureCount = captureCount,
    )

    private fun progressOf(species: List<SpeciesSummary>) = DexProgressMath.compute(
        regionId = "pacific",
        regionName = "Pacific USA",
        species = species.map {
            DexProgressMath.SpeciesRow(it.id, it.source, it.taxClass, it.kingdom, it.caught)
        },
        memberships = species.flatMap { s ->
            s.ecosystemIds.map { DexProgressMath.MembershipRow(s.id, it) }
        },
        ecosystems = listOf(Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1)),
    )

    @Test
    fun `the fraction counts curated species and the addendum stays outside it`() {
        val species = listOf(
            summary("owl", caughtAt = 100L),
            summary("heron"),
            summary("thrush", source = SpeciesSource.USER, caughtAt = 200L),
        )
        val state = buildStatsUiState(progressOf(species), species)

        assertEquals(1, state.overall.caught)
        assertEquals(2, state.overall.total)
        assertEquals(1, state.overall.userAdded)
        assertEquals(50, state.percentCaught)
        assertTrue(summaryLine(state).contains("+1 of your own"))
    }

    @Test
    fun `a nearly complete dex never rounds up to a hundred percent`() {
        val species = (1..120).map { summary("s$it", caughtAt = if (it < 120) 1L else null) }
        assertEquals(99, buildStatsUiState(progressOf(species), species).percentCaught)
    }

    @Test
    fun `the recent strip lists species, newest catch first`() {
        val species = listOf(
            summary("owl", caughtAt = 300L),
            summary("heron", caughtAt = 100L),
            summary("newt", caughtAt = 200L, taxClass = TaxClass.AMPHIBIAN),
            summary("uncaught"),
        )
        val state = buildStatsUiState(progressOf(species), species)

        assertEquals(listOf("owl", "newt", "heron"), state.recent.map { it.speciesId })
        assertEquals(300L, state.lastCatchAt)
    }

    @Test
    fun `a second photo of an old catch moves neither the strip nor the last-catch date`() {
        val before = listOf(
            summary("owl", caughtAt = 300L),
            summary("heron", caughtAt = 100L),
        )
        // S08 is about catches, not captures: adding a photo to the heron raises its capture
        // count and changes nothing about when it was caught.
        val after = listOf(
            summary("owl", caughtAt = 300L),
            summary("heron", caughtAt = 100L, captureCount = 2),
        )

        val a = buildStatsUiState(progressOf(before), before)
        val b = buildStatsUiState(progressOf(after), after)
        assertEquals(a.recent.map { it.speciesId }, b.recent.map { it.speciesId })
        assertEquals(a.lastCatchAt, b.lastCatchAt)
    }

    @Test
    fun `an empty collection says nothing rather than zero`() {
        val species = listOf(summary("owl"), summary("heron"))
        val state = buildStatsUiState(progressOf(species), species)

        assertTrue(state.recent.isEmpty())
        assertNull(state.lastCatchAt)
        assertEquals("0% caught", summaryLine(state))
    }

    @Test
    fun `the strip is capped`() {
        val species = (1..30).map { summary("s$it", caughtAt = it.toLong()) }
        assertEquals(RECENT_CATCH_LIMIT, buildStatsUiState(progressOf(species), species).recent.size)
    }

    @Test
    fun `class rows carry a label for every class the catalogue uses`() {
        val species = listOf(
            summary("owl", caughtAt = 1L),
            summary("newt", taxClass = TaxClass.AMPHIBIAN),
            summary("slug", taxClass = TaxClass.OTHER_INVERTEBRATE),
        )
        val state = buildStatsUiState(progressOf(species), species)

        assertEquals(
            listOf("Birds", "Amphibians", "Other invertebrates"),
            state.classes.map { it.label },
        )
        assertEquals(1, state.classes.first { it.taxClass == TaxClass.BIRD }.meter.caught)
    }

    // --- the third kingdom (DESIGN-identification.md 8.1) ---------------------------
    //
    // These build the `DexProgress` by hand instead of going through `progressOf`.
    // `DexProgressMath.compute` does not populate `DexProgress.fungi` or
    // `EcosystemProgress.fungi` yet — both fields exist with a `Meter(0, 0, 0)` default and
    // nothing passes them — and `domain/` is not this change's to edit. So what is under
    // test here is what this file owns: given a progress carrying a fungal meter, the Stats
    // state renders three kingdoms rather than two.

    private fun withFungi(
        species: List<SpeciesSummary>,
        fungi: Meter,
        perEcosystemFungi: Meter = Meter(0, 0, 0),
    ) = progressOf(species).let { base ->
        base.copy(
            fungi = fungi,
            perEcosystem = base.perEcosystem.map { it.copy(fungi = perEcosystemFungi) },
        )
    }

    @Test
    fun `a region with fungi shows a third kingdom and never blends the fractions`() {
        val species = listOf(summary("owl", caughtAt = 1L), summary("fir", taxClass = TaxClass.TREE))
        val state = buildStatsUiState(withFungi(species, Meter(2, 30, 1)), species)

        assertTrue(state.showPlants)
        assertTrue(state.showFungi)
        assertTrue(state.multipleKingdoms)
        // D13: three separate life lists. Nothing on the screen adds 1/1, 0/1 and 2/30 up.
        assertEquals(1, state.overall.caught)
        assertEquals(30, state.fungi.total)
        assertEquals("By ecosystem · animals / plants / fungi", ecosystemHeader(state))
        // The percentage is dropped the moment there is more than one kingdom to blend.
        assertTrue(summaryLine(state).none { it == '%' })
    }

    @Test
    fun `the addendum counts user-added species from all three kingdoms`() {
        val species = listOf(
            summary("owl", caughtAt = 1L),
            summary("thrush", source = SpeciesSource.USER, caughtAt = 2L),
            summary("fir", taxClass = TaxClass.TREE, source = SpeciesSource.USER, caughtAt = 3L),
        )
        val state = buildStatsUiState(withFungi(species, Meter(0, 30, 2)), species)

        assertEquals(4, state.userAdded)
        assertTrue(summaryLine(state).contains("+4 of your own"))
    }

    @Test
    fun `class rows group into three kingdoms`() {
        val species = listOf(
            summary("owl", caughtAt = 1L),
            summary("fir", taxClass = TaxClass.TREE),
            summary("chanterelle", taxClass = TaxClass.MUSHROOM, caughtAt = 4L),
            summary("conk", taxClass = TaxClass.BRACKET),
        )
        val state = buildStatsUiState(withFungi(species, Meter(1, 2)), species)

        assertEquals(listOf("Birds"), state.animalClasses.map { it.label })
        assertEquals(listOf("Trees"), state.plantClasses.map { it.label })
        assertEquals(listOf("Mushrooms", "Brackets"), state.fungusClasses.map { it.label })
        // Every class the catalogue can hold has a label, or a fungal group would render a
        // bar with an empty name.
        assertTrue(TaxClass.entries.none { classLabel(it).isBlank() })
    }

    @Test
    fun `a catalogue with no fungi renders exactly as the two-kingdom screen did`() {
        val species = listOf(summary("owl", caughtAt = 1L), summary("fir", taxClass = TaxClass.TREE))
        val state = buildStatsUiState(progressOf(species), species)

        assertFalse(state.showFungi)
        assertTrue(state.fungusClasses.isEmpty())
        assertEquals("By ecosystem · animals / plants", ecosystemHeader(state))
    }

    @Test
    fun `a region with only animals keeps the shipped one-kingdom header`() {
        val species = listOf(summary("owl", caughtAt = 1L), summary("heron"))
        val state = buildStatsUiState(progressOf(species), species)

        assertFalse(state.multipleKingdoms)
        assertEquals("By ecosystem", ecosystemHeader(state))
        assertTrue(summaryLine(state).contains("50% caught"))
    }

    @Test
    fun `a multi-ecosystem species counts in each of its ecosystems`() {
        val species = listOf(
            summary("owl", caughtAt = 1L, ecosystemIds = listOf("coastal-rainforest", "alpine")),
        )
        val progress = DexProgressMath.compute(
            regionId = "pacific",
            regionName = "Pacific USA",
            species = species.map {
                DexProgressMath.SpeciesRow(it.id, it.source, it.taxClass, it.kingdom, it.caught)
            },
            memberships = species.flatMap { s ->
                s.ecosystemIds.map { DexProgressMath.MembershipRow(s.id, it) }
            },
            ecosystems = listOf(
                Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
                Ecosystem("alpine", "pacific", "Sierra/Cascade Alpine", 2),
            ),
        )
        val state = buildStatsUiState(progress, species)

        assertEquals(2, state.ecosystems.size)
        assertTrue(state.ecosystems.all { it.animals.caught == 1 && it.animals.total == 1 })
    }
}
