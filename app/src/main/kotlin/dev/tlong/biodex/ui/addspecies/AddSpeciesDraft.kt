package dev.tlong.biodex.ui.addspecies

import dev.tlong.biodex.data.net.LookupOutcome
import dev.tlong.biodex.domain.PlaceAnswer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The hand-off into the add card (ARCHITECTURE.md 6.1). A photo URI and a lookup result do not
 * belong in route arguments, so the route carries a `draftId` keying this in-memory holder.
 *
 * In memory means exactly that: a draft does not survive process death, and the Confirm route
 * must render a "start again" state rather than crash when its draft is gone. Persisting it
 * would mean a table for something whose whole life is two screens.
 */
data class AddSpeciesDraft(
    val id: String,
    /** The name searched for on the grid or the Identify screen (D69, D78). */
    val typedName: String,
    /** Set when this draft is M20's backfill of an existing details-pending species. */
    val backfillSpeciesId: String? = null,
    /** A lookup the detail screen already ran, so the card does not repeat it. */
    val prefetched: LookupOutcome? = null,
    /** D78: the photo the Identify screen holds, so adding also captures (Q02). */
    val photo: DraftPhoto? = null,
) {
    val isBackfill: Boolean get() = backfillSpeciesId != null
}

/** A picked photo and its place — null when the photo's EXIF carries one (D60). */
data class DraftPhoto(val uri: String, val place: PlaceAnswer?)

class AddSpeciesDraftHolder(private val newId: () -> String = { UUID.randomUUID().toString() }) {

    private val drafts = ConcurrentHashMap<String, AddSpeciesDraft>()

    fun put(
        typedName: String,
        backfillSpeciesId: String? = null,
        prefetched: LookupOutcome? = null,
        photo: DraftPhoto? = null,
    ): String {
        val draft = AddSpeciesDraft(newId(), typedName, backfillSpeciesId, prefetched, photo)
        drafts[draft.id] = draft
        return draft.id
    }

    fun get(id: String): AddSpeciesDraft? = drafts[id]

    fun remove(id: String) {
        drafts.remove(id)
    }
}
