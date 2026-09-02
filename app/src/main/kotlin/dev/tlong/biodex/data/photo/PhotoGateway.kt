package dev.tlong.biodex.data.photo

/**
 * Everything registration needs from the platform, as one narrow interface.
 *
 * This is the seam ARCHITECTURE.md's slice-5 brief asks for: the decisions above it
 * ([CaptureRegistrar]) are ordinary Kotlin the JVM suite drives with a fake, and everything
 * below it — the content resolver, the persistable grant, `ImageDecoder`, `ExifInterface` —
 * is a thin shell with no branching worth testing off-device ([AndroidPhotoGateway]).
 */
interface PhotoGateway {

    /**
     * `takePersistableUriPermission` (4.1 step 1). Returns false when the provider refuses a
     * persistable grant — a real state on some picker/OS combinations, and **not** a failure:
     * registration proceeds, and the reference degrades to "thumbnail always, full photo
     * while the process-scoped grant lives".
     */
    fun persistGrant(uri: String): Boolean

    /** `releasePersistableUriPermission`, swallowing the throw when the grant is already gone. */
    fun releaseGrant(uri: String)

    /** How many persistable grants this app holds, against Android's 5,000 cap (4.4). */
    fun persistedGrantCount(): Int

    /** 4.1 step 2. Never throws: an unreadable stream yields [ExifFacts.None]. */
    fun readExif(uri: String): ExifFacts

    /**
     * 4.1 step 3 / 4.3: decode, scale to 640 px on the long edge, write
     * `thumbnails/<captureId>.jpg` under `filesDir`. Returns the **relative** path, or null
     * when the photo could not be decoded — which aborts the registration, because a capture
     * row without its thumbnail is exactly the row M11/M12 cannot honour.
     */
    fun writeThumbnail(captureId: String, uri: String): String?

    /** S03's escape hatch; only called when "keep a local copy" is on (default off). */
    fun writeLocalCopy(captureId: String, uri: String): String?

    /** Deletes an app-owned file named by a path relative to `filesDir`. Never throws. */
    fun deleteOwnedFile(relativePath: String)

    /** Probes the gallery reference. Full-size views only — the grid never calls this (M11). */
    fun resolve(photoUri: String, localCopyPath: String?): PhotoRef

    /** Best-effort display name for the picked file, for the Register screen's photo row. */
    fun displayName(uri: String): String?
}
