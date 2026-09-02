package dev.tlong.biodex.ui.detail

import dev.tlong.biodex.domain.Capture
import dev.tlong.biodex.domain.DexProgress
import dev.tlong.biodex.domain.Ecosystem
import dev.tlong.biodex.domain.Kingdom
import dev.tlong.biodex.domain.SpeciesDetail
import dev.tlong.biodex.domain.UsesNote
import dev.tlong.biodex.media.CallPlayback
import dev.tlong.biodex.media.CallRowState
import dev.tlong.biodex.media.callRowState
import dev.tlong.biodex.ui.common.UsesContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Frame 2's state (M03/M04/M05). Like the grid, the composition is a pure function over cold
 * flows so the JVM suite can check it without a device (ARCHITECTURE.md 6.2).
 */
data class EntryDetailUiState(
    val detail: SpeciesDetail? = null,
    /** The species' ecosystems as display names, in the catalogue's sort order. */
    val ecosystemNames: List<String> = emptyList(),
    /** The user's own photos, newest first (M04). Rendered from thumbnails only (M11). */
    val captures: List<Capture> = emptyList(),
    /** The counter the unlock reveal shows; also what the header would read after a catch. */
    val caughtCount: Int = 0,
    val totalCount: Int = 0,
    /** App-wide playback, filtered down to this row by `callRowState` (M06). */
    val playback: CallPlayback = CallPlayback.Idle,
    /** 5.3's network probe. Distinguishes "not cached yet" from "failed" in both media slots. */
    val online: Boolean = true,
    val loading: Boolean = true,
) {
    val missing: Boolean get() = !loading && detail == null

    val favoriteCaptureId: String? get() = captures.firstOrNull()?.id

    /**
     * The call row's whole state, decided in one pure place rather than in the composable —
     * and **null for a plant in every playback state** (M24, D15). A plant has no call slot
     * at all, so "disabled" is the wrong answer here: the row does not exist.
     */
    val callRow: CallRowState?
        get() {
            if (detail?.summary?.kingdom == Kingdom.PLANT) return null
            return callRowState(
                callUrl = detail?.callUrl,
                callAttribution = detail?.callAttribution,
                playback = playback,
                online = online,
            )
        }

    /**
     * What fills the call row's slot instead (M24). Null for an animal, and null for a plant
     * with **nothing to say** — no use tags and no caution — in which case habitat is followed
     * straight by the photo strip and nothing is drawn, not an empty section.
     *
     * The test is "has this plant anything to say", not "has this plant a use tag". A caution
     * outlives its tags: `keptUsesNote` keeps a `Caution:` sentence when the uses are empty and
     * drops only the rest, so Western Wild Ginger carries a warning about a carcinogen with no
     * tag on it at all. Gating on `uses.isNotEmpty()` swallowed exactly that warning — the one
     * thing on this screen a person could be hurt by not seeing.
     *
     * The caution is detected with [UsesNote.cautionSplit], the same function the write paths
     * use to decide what to keep, so the screen and the store cannot disagree about what counts
     * as a caution.
     */
    val uses: UsesContent?
        get() {
            val species = detail ?: return null
            if (species.summary.kingdom != Kingdom.PLANT) return null
            val (_, caution) = UsesNote.cautionSplit(species.usesNote)
            if (species.summary.uses.isEmpty() && caution == null) return null
            return UsesContent(
                uses = species.summary.uses,
                usesNote = species.usesNote,
                medicinalActivities = species.medicinalActivities,
                medicinalRecordCount = species.medicinalRecordCount,
                usesAttribution = species.usesAttribution,
            )
        }
}

fun entryDetailUiState(
    detail: Flow<SpeciesDetail?>,
    ecosystems: Flow<List<Ecosystem>>,
    captures: Flow<List<Capture>>,
    progress: Flow<DexProgress>,
    playback: Flow<CallPlayback>,
    online: Flow<Boolean>,
): Flow<EntryDetailUiState> {
    // The typed `combine` overloads stop at five sources, and this screen now has six.
    // Composing two of them keeps the arity honest without an untyped array version.
    val fromRepository = combine(detail, ecosystems, captures, progress) { species, ecos, caps, prog ->
        EntryDetailUiState(
            detail = species,
            ecosystemNames = ecosystemNamesFor(species, ecos),
            captures = caps,
            // S10's reveal counts the species' own kingdom — "4 / 80 plants", never the
            // two lists added together. Slice 11 adds the label; the number is right from
            // the moment plants exist, which is what stops the reveal reading 1/200 for
            // the hours between slice 10's asset and slice 11's UI.
            caughtCount = species?.let { prog.meterFor(it.summary.kingdom).caught }
                ?: prog.caughtCount,
            totalCount = species?.let { prog.meterFor(it.summary.kingdom).total }
                ?: prog.totalSpecies,
            loading = false,
        )
    }
    return combine(fromRepository, playback, online) { base, play, net ->
        base.copy(playback = play, online = net)
    }
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
