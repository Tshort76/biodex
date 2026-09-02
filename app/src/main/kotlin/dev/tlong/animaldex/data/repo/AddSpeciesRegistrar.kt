package dev.tlong.animaldex.data.repo

import dev.tlong.animaldex.data.photo.CaptureRegistrar
import dev.tlong.animaldex.domain.LookupFields
import dev.tlong.animaldex.domain.SpeciesFields
import dev.tlong.animaldex.domain.UserSpeciesRecord
import dev.tlong.animaldex.domain.detailsPendingFor
import dev.tlong.animaldex.domain.nextUserDexNumber
import dev.tlong.animaldex.domain.previewFields
import java.util.UUID

/**
 * The user-added write path (M18–M21). Two entry points and one rule between them: [create]
 * writes a species the user just accepted, [backfill] updates one that already exists — and
 * only [backfill] is allowed to touch fields a lookup produced, because only it knows which
 * fields the user has since edited.
 *
 * Ordering in [create] is forced by the foreign key: the species row must exist before a
 * capture can reference it. If the photo then turns out to be unreadable, the species row is
 * deleted again — it has no captures yet, so the cascade takes nothing with it. Duplicating
 * the registrar's thumbnail check here to invert the order would fork the one piece of logic
 * M11 depends on.
 */
class AddSpeciesRegistrar(
    private val store: UserSpeciesStore,
    private val captures: CaptureRegistrar,
    private val regionId: String = DEFAULT_REGION_ID,
    private val newSpeciesId: () -> String = { "user-" + UUID.randomUUID().toString() },
) {

    sealed interface CreateResult {
        data class Created(val speciesId: String, val dexNumber: Int) : CreateResult

        /** The photo could not be read; nothing survives — not the species, not a capture. */
        data object PhotoUnreadable : CreateResult
    }

    /**
     * Writes the accepted card (M19) or, offline, the name-and-photo-only row (M20). The two
     * differ in one thing: whether [fields] carries a scientific name, which is what
     * `detailsPendingFor` reads.
     */
    suspend fun create(
        fields: SpeciesFields,
        ecosystemIds: List<String>,
        photoUri: String?,
        userEditedFields: List<String> = emptyList(),
    ): CreateResult {
        val record = UserSpeciesRecord(
            id = newSpeciesId(),
            regionId = regionId,
            dexNumber = nextUserDexNumber(store.maxUserDexNumber(regionId)),
            detailsPending = detailsPendingFor(fields),
            fields = fields,
            userEditedFields = userEditedFields,
        )
        store.upsertUserSpecies(record, ecosystemIds)

        if (photoUri != null) {
            val registered = captures.register(record.id, photoUri)
            if (registered is CaptureRegistrar.RegisterResult.ThumbnailFailed) {
                store.deleteUserSpecies(record.id)
                return CreateResult.PhotoUnreadable
            }
        }
        return CreateResult.Created(record.id, record.dexNumber)
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
