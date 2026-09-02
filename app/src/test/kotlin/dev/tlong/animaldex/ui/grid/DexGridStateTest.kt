package dev.tlong.animaldex.ui.grid

import dev.tlong.animaldex.domain.DexProgress
import dev.tlong.animaldex.domain.Ecosystem
import dev.tlong.animaldex.domain.Meter
import dev.tlong.animaldex.domain.SpeciesSource
import dev.tlong.animaldex.domain.SpeciesSummary
import dev.tlong.animaldex.domain.TaxClass
import dev.tlong.animaldex.domain.USER_DEX_NUMBER_BASE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's search and filter composition (M14). Nothing here touches Room, Android or a
 * ViewModel: `dexGridUiState` is the same function the ViewModel wraps, and the "repository"
 * is three MutableStateFlows — which is what makes M14 checkable with no phone attached.
 */
class DexGridStateTest {

    // ---- the fake catalogue: 120 curated species plus one user-added ----------------

    private fun species(
        n: Int,
        name: String,
        taxClass: TaxClass,
        ecosystems: List<String>,
        caught: Boolean = false,
        scientific: String? = null,
        source: SpeciesSource = SpeciesSource.CURATED,
    ) = SpeciesSummary(
        id = "sp-$n",
        regionId = "pacific",
        dexNumber = n,
        source = source,
        detailsPending = false,
        commonName = name,
        scientificName = scientific,
        taxClass = taxClass,
        silhouetteRes = "sil_" + taxClass.wireName,
        ecosystemIds = ecosystems,
        caughtAt = if (caught) 1_756_512_000_000L else null,
        thumbPath = null,
        captureCount = if (caught) 1 else 0,
    )

    private val named = listOf(
        species(3, "Great Blue Heron", TaxClass.BIRD, listOf("riparian-wetland")),
        species(
            21,
            "Western Screech-Owl",
            TaxClass.BIRD,
            listOf("oak-chaparral", "riparian-wetland"),
            caught = true,
            scientific = "Megascops kennicottii",
        ),
        species(
            66,
            "Western Fence Lizard",
            TaxClass.REPTILE,
            listOf("oak-chaparral"),
            scientific = "Sceloporus occidentalis",
        ),
        species(67, "Western Tanager", TaxClass.BIRD, listOf("alpine")),
        species(88, "Banana Slug", TaxClass.OTHER_INVERTEBRATE, listOf("coastal-rainforest")),
    )

    /** 120 curated species; the five named ones sit at their own dex numbers. */
    private val catalogue: List<SpeciesSummary> = buildList {
        val taken = named.associateBy { it.dexNumber }
        for (n in 1..120) {
            add(taken[n] ?: species(n, "Filler Species $n", TaxClass.MAMMAL, listOf("alpine")))
        }
    }

    private val userAdded = species(
        USER_DEX_NUMBER_BASE + 1,
        "Varied Thrush",
        TaxClass.BIRD,
        listOf("coastal-rainforest"),
        source = SpeciesSource.USER,
    )

    private val ecosystems = listOf(
        Ecosystem("coastal-rainforest", "pacific", "Coastal Rainforest", 1),
        Ecosystem("oak-chaparral", "pacific", "Oak Woodland & Chaparral", 3),
        Ecosystem("riparian-wetland", "pacific", "Riparian & Wetland", 4),
        Ecosystem("alpine", "pacific", "Sierra/Cascade Alpine", 6),
    )

    private val progress = DexProgress(
        regionId = "pacific",
        overall = Meter(caught = 1, total = 120, userAdded = 1),
        perClass = emptyList(),
        perEcosystem = emptyList(),
    )

    // ---- the composition under test ------------------------------------------------

    private val speciesFlow = MutableStateFlow(catalogue + userAdded)
    private val query = MutableStateFlow("")
    private val filters = MutableStateFlow(DexGridFilters())

    private fun state(): DexGridUiState = runBlocking {
        dexGridUiState(
            species = speciesFlow,
            ecosystems = MutableStateFlow(ecosystems),
            progress = MutableStateFlow(progress),
            query = query,
            filters = filters,
        ).first()
    }

    private fun names(): List<String> = state().species.map { it.commonName }

    // ---- search --------------------------------------------------------------------

    @Test
    fun `search narrows to name matches and is case-insensitive`() {
        query.value = "western"
        assertEquals(
            listOf("Western Screech-Owl", "Western Fence Lizard", "Western Tanager"),
            names(),
        )
        query.value = "WESTERN"
        assertEquals(3, names().size)
    }

    @Test
    fun `search also matches the scientific name`() {
        query.value = "sceloporus"
        assertEquals(listOf("Western Fence Lizard"), names())
    }

    @Test
    fun `clearing the search restores the whole catalogue`() {
        query.value = "western"
        assertEquals(3, state().species.size)
        query.value = ""
        assertEquals(121, state().species.size)
    }

    // ---- filters -------------------------------------------------------------------

    @Test
    fun `class and ecosystem chips compose rather than override`() {
        filters.value = DexGridFilters(
            taxClass = TaxClass.BIRD,
            ecosystemId = "oak-chaparral",
        )
        // Only the owl is both a bird and tagged oak-chaparral; the lizard shares the
        // ecosystem and the heron and tanager share the class, and all three are excluded.
        assertEquals(listOf("Western Screech-Owl"), names())
    }

    @Test
    fun `search composes with both chips`() {
        query.value = "western"
        filters.value = DexGridFilters(taxClass = TaxClass.REPTILE)
        assertEquals(listOf("Western Fence Lizard"), names())

        filters.value = DexGridFilters(taxClass = TaxClass.REPTILE, ecosystemId = "alpine")
        assertTrue(names().isEmpty())
    }

    @Test
    fun `caught filter splits the catalogue and composes with search`() {
        filters.value = DexGridFilters(caught = CaughtFilter.CAUGHT)
        assertEquals(listOf("Western Screech-Owl"), names())

        filters.value = DexGridFilters(caught = CaughtFilter.UNCAUGHT)
        assertEquals(120, state().species.size)
        assertFalse(names().contains("Western Screech-Owl"))

        query.value = "western"
        assertEquals(listOf("Western Fence Lizard", "Western Tanager"), names())
    }

    @Test
    fun `clearing every filter restores the whole catalogue`() {
        filters.value = DexGridFilters(
            caught = CaughtFilter.CAUGHT,
            taxClass = TaxClass.BIRD,
            ecosystemId = "alpine",
        )
        assertTrue(state().species.isEmpty())
        filters.value = DexGridFilters()
        assertEquals(121, state().species.size)
        assertTrue(state().filters.isEmpty)
    }

    // ---- ordering and the header ---------------------------------------------------

    @Test
    fun `grid stays in dex order with user-added species trailing`() {
        val numbers = state().species.map { it.dexNumber }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(userAdded.commonName, names().last())
    }

    @Test
    fun `header reads the region and the curated fraction, excluding user-added species`() {
        val s = state()
        assertEquals("Pacific", s.regionLabel)
        assertEquals(1, s.caughtCount)
        assertEquals(120, s.totalCount)
        assertFalse(s.loading)
    }

    @Test
    fun `an empty database reads as loading, not as an empty catalogue`() {
        speciesFlow.value = emptyList()
        assertTrue(
            runBlocking {
                dexGridUiState(
                    species = speciesFlow,
                    ecosystems = MutableStateFlow(emptyList()),
                    progress = MutableStateFlow(DexProgress.Empty),
                    query = query,
                    filters = filters,
                ).first()
            }.loading,
        )
    }

    @Test
    fun `a search matching nothing is an empty result, not a loading state`() {
        query.value = "no such animal"
        val s = state()
        assertTrue(s.species.isEmpty())
        assertFalse(s.loading)
        assertTrue(s.isFiltered)
    }
}
