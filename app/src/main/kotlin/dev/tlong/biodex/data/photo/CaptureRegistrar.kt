package dev.tlong.biodex.data.photo

import dev.tlong.biodex.domain.Capture
import java.util.UUID

/**
 * The core loop's write path (M09–M13, S04, S07), orchestrating [PhotoGateway] and
 * [CaptureStore]. Every branch here is ordinary Kotlin: the JVM suite drives the whole of
 * registration, deletion, favouriting and re-linking with a fake gateway and an in-memory
 * store, so what is left unverified is only what Android itself does.
 *
 * Ordering is ARCHITECTURE.md 4.1, and it is deliberate: the thumbnail is written **before**
 * the transaction, so a capture row can never exist without the one rendering of it the app
 * is guaranteed to keep (M11).
 */
class CaptureRegistrar(
    private val store: CaptureStore,
    private val photos: PhotoGateway,
    private val newCaptureId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    /** S03, default off. Slice 8's settings toggle replaces this lambda; nothing else changes. */
    private val keepLocalCopy: () -> Boolean = { false },
) {

    sealed interface RegisterResult {
        data class Registered(
            val speciesId: String,
            val captureId: String,
            /** True when this capture unlocked the species — the reveal's trigger (M09). */
            val isFirst: Boolean,
        ) : RegisterResult

        /** The photo could not be turned into a thumbnail; nothing was written. */
        data class ThumbnailFailed(val photoUri: String) : RegisterResult
    }

    /**
     * Registers one gallery photo against one species. No copy of the photo is stored unless
     * [keepLocalCopy] says otherwise (M10, D6) — what the app owns is the thumbnail.
     */
    suspend fun register(
        speciesId: String,
        photoUri: String?,
        note: String? = null,
        locationLabel: String? = null,
    ): RegisterResult {
        // M41. A photoless capture skips the grant, the EXIF read and the thumbnail entirely —
        // there is nothing to take a grant on, nothing to read a date out of, and nothing to
        // render. Its `takenAt` is the registration time, which is the honest answer.
        if (photoUri == null) return registerWithoutPhoto(speciesId, note, locationLabel)

        val alreadyReferenced = store.captureCountForUri(photoUri) > 0
        // 4.1 step 1. A refusal is tolerated: the picker's own grant lasts long enough to
        // build the thumbnail, which is the durable artifact either way.
        val grantPersisted = photos.persistGrant(photoUri)

        val facts = photos.readExif(photoUri)
        val captureId = newCaptureId()
        val thumbPath = photos.writeThumbnail(captureId, photoUri)
        if (thumbPath == null) {
            // Leave the gallery no worse off: drop a grant we took only for this attempt.
            if (grantPersisted && !alreadyReferenced) photos.releaseGrant(photoUri)
            return RegisterResult.ThumbnailFailed(photoUri)
        }

        val registeredAt = now()
        val plan = planRegistration(
            capture = Capture(
                id = captureId,
                speciesId = speciesId,
                photoUri = photoUri,
                thumbPath = thumbPath,
                localCopyPath = if (keepLocalCopy()) {
                    photos.writeLocalCopy(captureId, photoUri)
                } else {
                    null
                },
                takenAt = takenAtOrFallback(facts, registeredAt),
                lat = facts.lat,
                lng = facts.lng,
                locationLabel = locationLabel,
                note = note,
                createdAt = registeredAt,
            ),
            existingEntry = store.entryOnce(speciesId),
        )
        store.applyRegistration(plan)
        return RegisterResult.Registered(speciesId, captureId, plan.isFirst)
    }

    /**
     * A capture with no photograph (M41). Everything else about a catch is still recorded —
     * the species, the date, the place, the note — which is what makes "seen again, here, on
     * this date" mean something for a plant that will never carry a picture of its own.
     */
    private suspend fun registerWithoutPhoto(
        speciesId: String,
        note: String?,
        locationLabel: String?,
    ): RegisterResult {
        val captureId = newCaptureId()
        val registeredAt = now()
        val plan = planRegistration(
            capture = Capture(
                id = captureId,
                speciesId = speciesId,
                photoUri = null,
                thumbPath = null,
                localCopyPath = null,
                takenAt = registeredAt,
                locationLabel = locationLabel,
                note = note,
                createdAt = registeredAt,
            ),
            existingEntry = store.entryOnce(speciesId),
        )
        store.applyRegistration(plan)
        return RegisterResult.Registered(speciesId, captureId, plan.isFirst)
    }

    /**
     * S07. Removes the reference, the thumbnail and any local copy, and — only when the last
     * capture goes — the entry. The gallery photo is never touched, and the grant is released
     * only when no other capture still needs it.
     */
    suspend fun deleteCapture(captureId: String): CaptureDeletionPlan? {
        val capture = store.captureOnce(captureId) ?: return null
        val plan = planCaptureDeletion(
            capture = capture,
            speciesCaptures = store.capturesForSpecies(capture.speciesId),
            favoriteCaptureId = store.entryOnce(capture.speciesId)?.favoriteCaptureId,
            // Never asked for a photoless capture: the count is keyed on a URI, and a null
            // matches no row in SQL, so the answer would be a meaningless zero.
            uriReferenceCount = capture.photoUri?.let { store.captureCountForUri(it) } ?: 0,
        )
        store.applyDeletion(plan)
        plan.filesToDelete.forEach(photos::deleteOwnedFile)
        plan.releaseUri?.let(photos::releaseGrant)
        return plan
    }

    /** S04: one favorite per entry; it becomes the grid thumbnail. */
    suspend fun setFavorite(speciesId: String, captureId: String) {
        store.setFavoriteCapture(speciesId, captureId)
    }

    /**
     * 4.2's re-link: the user points a broken capture at a photo that still exists. The
     * capture keeps its id, its date and its note — only the reference and the thumbnail
     * change, so nothing about the catch moves.
     */
    suspend fun relink(captureId: String, newPhotoUri: String): Boolean {
        val capture = store.captureOnce(captureId) ?: return false
        val alreadyReferenced = store.captureCountForUri(newPhotoUri) > 0
        val grantPersisted = photos.persistGrant(newPhotoUri)
        val thumbPath = photos.writeThumbnail(captureId, newPhotoUri)
        if (thumbPath == null) {
            // The old reference is still the best thing we have; leave it, and hand back the
            // grant we took only for this attempt.
            if (grantPersisted && !alreadyReferenced) photos.releaseGrant(newPhotoUri)
            return false
        }
        val plan = planRelink(
            capture = capture,
            newPhotoUri = newPhotoUri,
            uriReferenceCount = capture.photoUri?.let { store.captureCountForUri(it) } ?: 0,
        )
        store.updateCaptureReference(captureId, plan.newPhotoUri, thumbPath)
        plan.releaseUri?.let(photos::releaseGrant)
        return true
    }

    fun resolve(capture: Capture): PhotoRef = photos.resolve(capture.photoUri, capture.localCopyPath)

    fun grantPressure(): GrantPressure = grantPressure(photos.persistedGrantCount())
}
