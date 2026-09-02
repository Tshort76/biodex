package dev.tlong.biodex.ui.stats

import dev.tlong.biodex.domain.DexProgress
import dev.tlong.biodex.domain.EcosystemProgress
import dev.tlong.biodex.domain.Meter
import dev.tlong.biodex.domain.SpeciesSummary
import dev.tlong.biodex.domain.TaxClass
import dev.tlong.biodex.ui.grid.regionLabelFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Frame 5 of `mockup.html` as data (M15/S08), composed by a pure function so the numbers can
 * be checked without a device — which matters here more than on any other screen, because
 * the slice's phone check is "the stats reconcile with the grid by hand-count".
 *
 * Two rules from D9 run through everything below and neither is negotiable:
 *  - **Only curated species are inside a fraction.** 47 / 120 counts the catalogue.
 *  - **User-added species are an addendum**, "+3 of your own", never a numerator or a
 *    denominator. `DexProgressMath` already enforces this; this file only renders it.
 */

data class StatsUiState(
    val regionLabel: String = "",
    val overall: Meter = Meter(0, 0, 0),
    val ecosystems: List<EcosystemProgress> = emptyList(),
    val classes: List<ClassRow> = emptyList(),
    val recent: List<RecentCatch> = emptyList(),
    /** Epoch millis of the newest *first* catch, or null when nothing is caught. */
    val lastCatchAt: Long? = null,
    val loading: Boolean = true,
) {
    /** The mockup's `39% caught`, rounded down so 119/120 never reads as 100%. */
    val percentCaught: Int
        get() = if (overall.total == 0) 0 else overall.caught * 100 / overall.total
}

data class ClassRow(val taxClass: TaxClass, val label: String, val meter: Meter)

/**
 * One tile of the "recently caught" strip. It is a *species*, not a capture: S08 asks for
 * recently caught things and for the date of the last new catch, so adding a second photo
 * of a bird caught last spring must not push it back to the front of the strip, and must
 * not move the last-catch date.
 */
data class RecentCatch(
    val speciesId: String,
    val commonName: String,
    val caughtAt: Long,
    val thumbPath: String?,
    val silhouetteRes: String,
    val taxClass: TaxClass,
)

/** How many tiles the strip holds. The mockup shows four; the row scrolls. */
const val RECENT_CATCH_LIMIT = 12

fun statsUiState(
    progress: Flow<DexProgress>,
    species: Flow<List<SpeciesSummary>>,
    regionLabel: (String) -> String = ::regionLabelFor,
): Flow<StatsUiState> = combine(progress, species) { dexProgress, allSpecies ->
    buildStatsUiState(dexProgress, allSpecies, regionLabel)
}

fun buildStatsUiState(
    progress: DexProgress,
    species: List<SpeciesSummary>,
    regionLabel: (String) -> String = ::regionLabelFor,
): StatsUiState {
    val caught = species.filter { it.caughtAt != null }.sortedByDescending { it.caughtAt }
    return StatsUiState(
        regionLabel = regionLabel(progress.regionId),
        overall = progress.overall,
        ecosystems = progress.perEcosystem,
        classes = progress.perClass.map { (taxClass, meter) ->
            ClassRow(taxClass, classLabel(taxClass), meter)
        },
        recent = caught.take(RECENT_CATCH_LIMIT).map { summary ->
            RecentCatch(
                speciesId = summary.id,
                commonName = summary.commonName,
                caughtAt = summary.caughtAt!!,
                thumbPath = summary.thumbPath,
                silhouetteRes = summary.silhouetteRes,
                taxClass = summary.taxClass,
            )
        },
        lastCatchAt = caught.firstOrNull()?.caughtAt,
        loading = species.isEmpty() && progress.totalSpecies == 0,
    )
}

/**
 * Seven bars, not the mockup's six. The mockup merges insects with other invertebrates
 * under "Invertebrates"; the catalogue, the filter chips and the silhouettes all treat them
 * as separate classes, and a stats screen that groups differently from the grid it is meant
 * to reconcile with is a screen that makes the user do arithmetic.
 */
fun classLabel(taxClass: TaxClass): String = when (taxClass) {
    TaxClass.BIRD -> "Birds"
    TaxClass.MAMMAL -> "Mammals"
    TaxClass.REPTILE -> "Reptiles"
    TaxClass.AMPHIBIAN -> "Amphibians"
    TaxClass.FISH -> "Fish"
    TaxClass.INSECT -> "Insects"
    TaxClass.OTHER_INVERTEBRATE -> "Other invertebrates"
}
