package dev.tlong.biodex.data.repo

import dev.tlong.biodex.data.photo.CaptureRegistrar
import dev.tlong.biodex.data.photo.keepsOwnPhoto
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
        // 11.1's write-path invariants — kingdom paired with class, uses plant-only, no note
        // without a use, no Duke's credit without Duke's data — are applied here and not only
        // on the card, so nothing that reaches the store can violate them.
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

        // M41 applies to a species the user adds exactly as it applies to a catalogue one: a
        // plant keeps no photograph, and its tile shows the reference image the lookup found.
        // The gate lives here rather than only on the card for the same reason the
        // normalization above does — this is the one door into the store.
        // The capture row is still written — a user species is caught by definition, and a
        // plant's capture is simply one with no photo, exactly as the Register screen writes.
        if (photoUri != null) {
            val kept = photoUri.takeIf { keepsOwnPhoto(normalized.kingdom) }
            val registered = captures.register(record.id, kept)
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
