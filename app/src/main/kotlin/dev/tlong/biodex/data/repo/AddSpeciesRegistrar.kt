package dev.tlong.biodex.data.repo

import dev.tlong.biodex.domain.LookupFields
import dev.tlong.biodex.domain.SpeciesFields
import dev.tlong.biodex.domain.UserSpeciesRecord
import dev.tlong.biodex.domain.detailsPendingFor
import dev.tlong.biodex.domain.nextUserDexNumber
import dev.tlong.biodex.domain.normalized
import dev.tlong.biodex.domain.previewFields
import java.util.UUID

/**
 * The user-added write path (M18–M21). Two entry points and one rule between them: [create]
 * writes a species the user just accepted, [backfill] updates one that already exists — and
 * only [backfill] is allowed to touch fields a lookup produced, because only it knows which
 * fields the user has since edited.
 *
 * Adding a species puts it in the dex and nothing more (D69): it is not a catch. Catching it
 * is the same Register flow every catalogue species goes through, so [CaptureRegistrar] stays
 * the one door a capture comes in by.
 */
class AddSpeciesRegistrar(
    private val store: UserSpeciesStore,
    private val regionId: String = DEFAULT_REGION_ID,
    private val newSpeciesId: () -> String = { "user-" + UUID.randomUUID().toString() },
) {

    data class Created(val speciesId: String, val dexNumber: Int)

    /**
     * Writes the accepted card (M19) or, offline, the name-only row (M20). The two differ in
     * one thing: whether [fields] carries a scientific name, which is what `detailsPendingFor`
     * reads.
     */
    suspend fun create(
        fields: SpeciesFields,
        ecosystemIds: List<String>,
        userEditedFields: List<String> = emptyList(),
    ): Created {
        // 11.1's write-path invariants — kingdom paired with class, no note without a use — are
        // applied here and not only on the card, so nothing that reaches the store can
        // violate them.
        val normalized = fields.normalized()
        val record = UserSpeciesRecord(
            id = newSpeciesId(),
            regionId = regionId,
            dexNumber = nextUserDexNumber(store.maxUserDexNumber(regionId)),
            detailsPending = detailsPendingFor(normalized),
            fields = normalized,
            userEditedFields = userEditedFields,
        )
        store.upsertUserSpecies(record, ecosystemIds)
        return Created(record.id, record.dexNumber)
    }

    /**
     * M20's backfill and M21's protection, together. The merge is where the invariant lives;
     * this method's own job is to pass the *stored* edited-field set, never the card's idea of
     * it, so a field the user edited three sessions ago is still safe today.
     *
     * [ecosystemIds] is null for an automatic backfill: ecosystem tags are the user's manual
     * pick (D10) and no lookup may touch them.
     */
    suspend fun backfill(
        speciesId: String,
        lookup: LookupFields?,
        edits: FieldEdits = FieldEdits.None,
        ecosystemIds: List<String>? = null,
    ): UserSpeciesRecord? {
        val existing = store.userSpecies(speciesId) ?: return null
        val edited = (existing.userEditedFields + edits.fields).distinct()
        val merged = previewFields(
            stored = existing.fields,
            lookup = lookup,
            lockedFields = edited.toSet(),
            editValues = edits.values,
            editedNow = edits.fields.toSet(),
        )
        val updated = existing.copy(
            fields = merged,
            userEditedFields = edited,
            detailsPending = detailsPendingFor(merged),
        )
        store.upsertUserSpecies(updated, ecosystemIds)
        return updated
    }

    /** The card's hand-edits: the values the user typed, and which fields they belong to. */
    data class FieldEdits(val values: SpeciesFields?, val fields: List<String>) {
        companion object {
            val None = FieldEdits(null, emptyList())
        }
    }
}
