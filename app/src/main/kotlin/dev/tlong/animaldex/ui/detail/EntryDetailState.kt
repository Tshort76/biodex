package dev.tlong.animaldex.ui.detail

import dev.tlong.animaldex.domain.Ecosystem
import dev.tlong.animaldex.domain.SpeciesDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Frame 2's state (M03/M05). Like the grid, the composition is a pure function over cold
 * flows so the JVM suite can check it without a device (ARCHITECTURE.md 6.2).
 */
data class EntryDetailUiState(
    val detail: SpeciesDetail? = null,
    /** The species' ecosystems as display names, in the catalogue's sort order. */
    val ecosystemNames: List<String> = emptyList(),
    val loading: Boolean = true,
) {
    val missing: Boolean get() = !loading && detail == null
}

fun entryDetailUiState(
    detail: Flow<SpeciesDetail?>,
    ecosystems: Flow<List<Ecosystem>>,
): Flow<EntryDetailUiState> = combine(detail, ecosystems) { species, ecos ->
    EntryDetailUiState(
        detail = species,
        ecosystemNames = ecosystemNamesFor(species, ecos),
        loading = false,
    )
}

/** Unknown ids are dropped rather than rendered raw; sort order is the catalogue's. */
internal fun ecosystemNamesFor(
    detail: SpeciesDetail?,
    ecosystems: List<Ecosystem>,
): List<String> {
    if (detail == null) return emptyList()
    val ids = detail.summary.ecosystemIds.toSet()
    return ecosystems.filter { it.id in ids }.sortedBy { it.sortOrder }.map { it.name }
}
