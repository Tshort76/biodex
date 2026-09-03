package dev.tlong.biodex.data.photo

import dev.tlong.biodex.domain.Capture
import dev.tlong.biodex.domain.Entry

/**
 * In-memory stand-ins for Room and for Android, so the whole core loop — register, delete,
 * favourite, re-link — runs in the JVM suite. The fake store reproduces the one piece of Room
 * behaviour the invariants depend on: the DAO's favorite-then-earliest thumbnail fallback.
 */
class FakeCaptureStore : CaptureStore {

    val captures = mutableMapOf<String, Capture>()
    val entries = mutableMapOf<String, Entry>()

    override suspend fun entryOnce(speciesId: String): Entry? = entries[speciesId]?.copy(
        captureCount = captures.values.count { it.speciesId == speciesId },
    )

    override suspend fun captureOnce(captureId: String): Capture? = captures[captureId]

    override suspend fun capturesForSpecies(speciesId: String): List<Capture> =
        captures.values.filter { it.speciesId == speciesId }.sortedBy { it.createdAt }

    override suspend fun captureCountForUri(photoUri: String): Int =
        captures.values.count { it.photoUri == photoUri }

    override suspend fun applyRegistration(plan: RegistrationPlan) {
        plan.newEntry?.let { entries[it.speciesId] = it }
        captures[plan.capture.id] = plan.capture
    }

    override suspend fun applyDeletion(plan: CaptureDeletionPlan) {
        if (plan.clearFavorite) {
            entries[plan.speciesId] = entries.getValue(plan.speciesId).copy(favoriteCaptureId = null)
        }
        captures.remove(plan.captureId)
        if (plan.deleteEntry) entries.remove(plan.speciesId)
    }

    override suspend fun setFavoriteCapture(speciesId: String, captureId: String?) {
        entries[speciesId] = entries.getValue(speciesId).copy(favoriteCaptureId = captureId)
    }

    override suspend fun updateCaptureReference(
        captureId: String,
        photoUri: String,
        thumbPath: String,
    ) {
        captures[captureId] = captures.getValue(captureId)
            .copy(photoUri = photoUri, thumbPath = thumbPath)
    }

    /** What the grid would render for a species — the DAO's COALESCE, in Kotlin. */
    fun renderedThumbPath(speciesId: String): String? {
        val entry = entries[speciesId] ?: return null
        val mine = captures.values.filter { it.speciesId == speciesId }
        return mine.firstOrNull { it.id == entry.favoriteCaptureId }?.thumbPath
            ?: mine.minByOrNull { it.createdAt }?.thumbPath
    }
}

class FakePhotoGateway(
    var thumbnailWorks: Boolean = true,
    var grantPersists: Boolean = true,
    var exif: ExifFacts = ExifFacts.None,
    var grantCount: Int = 3,
    var resolveResult: PhotoRef? = null,
    var uploadBytes: ByteArray? = byteArrayOf(1, 2, 3),
) : PhotoGateway {

    val persisted = mutableListOf<String>()
    val released = mutableListOf<String>()
    val deletedFiles = mutableListOf<String>()
    val writtenThumbnails = mutableListOf<String>()
    var localCopiesWritten = 0

    override fun persistGrant(uri: String): Boolean {
        if (grantPersists) persisted += uri
        return grantPersists
    }

    override fun releaseGrant(uri: String) {
        released += uri
    }

    override fun persistedGrantCount(): Int = grantCount

    override fun readExif(uri: String): ExifFacts = exif

    override fun writeThumbnail(captureId: String, uri: String): String? =
        if (thumbnailWorks) {
            thumbnailRelativePath(captureId).also { writtenThumbnails += it }
        } else {
            null
        }

    override fun writeLocalCopy(captureId: String, uri: String): String? {
        localCopiesWritten++
        return localCopyRelativePath(captureId)
    }

    override fun deleteOwnedFile(relativePath: String) {
        deletedFiles += relativePath
    }

    override fun resolve(photoUri: String, localCopyPath: String?): PhotoRef =
        resolveResult ?: PhotoRef.Available(photoUri)

    override fun displayName(uri: String): String? = uri.substringAfterLast('/')

    /** M36's re-encoded upload copy; null models a photo that would not decode. */
    override fun readForUpload(uri: String): ByteArray? =
        if (uploadBytes != null) uploadBytes else null
}
