package dev.tlong.animaldex.data.photo

import dev.tlong.animaldex.domain.Capture
import dev.tlong.animaldex.domain.Entry

/**
 * The write surface [CaptureRegistrar] needs, named as an interface so the JVM suite can run
 * the whole registration and deletion flow against in-memory maps. `DexRepository` is the one
 * real implementation and applies each plan in a single Room transaction.
 *
 * This is the same split slice 3 used for the catalogue importer (3.4): the invariants are
 * about the user's data, not about Room, so they are tested without it.
 */
interface CaptureStore {

    suspend fun entryOnce(speciesId: String): Entry?

    suspend fun captureOnce(captureId: String): Capture?

    suspend fun capturesForSpecies(speciesId: String): List<Capture>

    /** How many capture rows anywhere hold this exact `photoUri` — the shared-grant check. */
    suspend fun captureCountForUri(photoUri: String): Int

    /** One transaction: insert the capture and, when it is the first, create the entry. */
    suspend fun applyRegistration(plan: RegistrationPlan)

    /**
     * One transaction, in this order: null a dangling favorite, delete the capture, and drop
     * the entry when nothing is left. File deletion and grant release happen outside it.
     */
    suspend fun applyDeletion(plan: CaptureDeletionPlan)

    /** S04. Null clears the favorite, which makes the earliest capture the entry's face. */
    suspend fun setFavoriteCapture(speciesId: String, captureId: String?)

    suspend fun updateCaptureReference(captureId: String, photoUri: String, thumbPath: String)
}
