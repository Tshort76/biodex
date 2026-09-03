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

    /**
     * Probes the gallery reference. Full-size views only — the grid never calls this (M11).
     * A null [photoUri] is a photoless capture and resolves to [PhotoRef.None] without any
     * probe at all (M41).
     */
    fun resolve(photoUri: String?, localCopyPath: String?): PhotoRef

    /** Best-effort display name for the picked file, for the Register screen's photo row. */
    fun displayName(uri: String): String?

    /**
     * M36. The bytes an identification uploads: a JPEG **re-encoded from a decoded bitmap** at
     * [UPLOAD_LONG_EDGE_PX] on the long edge, or null when the photo could not be decoded.
     *
     * The re-encode is not an optimisation, it is the privacy mechanism. This app reads EXIF
     * GPS a few lines up (`readExif`), and a photo's coordinates are the user's home or a
     * favourite patch; decoding to a bitmap and compressing it again produces a file with no
     * EXIF at all, so **the thing that shrinks the photo is the thing that strips its
     * metadata**. The original file's bytes are never read into a request, which is why this
     * returns bytes rather than a path a caller could stream instead.
     */
    fun readForUpload(uri: String): ByteArray?

    // -----------------------------------------------------------------------
    // The in-app camera (M40, D26). Three calls, and all three are cache
    // bookkeeping rather than camera code: the system camera app takes the
    // photograph, and this app only says where to put it and what to do next.
    // -----------------------------------------------------------------------

    /**
     * Creates an empty file under `cacheDir/capture/` and returns the `FileProvider` URI the
     * camera intent's `EXTRA_OUTPUT` should name, or null if it could not be created.
     */
    fun newCameraCaptureUri(): String?

    /**
     * Copies a cache capture into `Pictures/BioDex/` and returns the `MediaStore` URI, or null
     * on failure. Called only at registration and only for a kingdom that keeps its photo
     * (D26) — which is what stops a plant's shot ever reaching the gallery.
     */
    fun promoteToGallery(cacheUri: String, displayName: String): String?

    /**
     * Deletes everything under `cacheDir/capture/`. Called on app start, so an abandoned
     * Register screen — the user backed out mid-flow, or the process was killed — leaves no
     * photograph behind in a directory nothing else ever cleans.
     */
    fun sweepCameraCache()
}

/**
 * The upload's long edge. Pl@ntNet's preferred size is **unverified** — the documentation was
 * not reachable when this was written — so 1,024 px is chosen as the size that is comfortably
 * enough for a classifier to work on a leaf or a flower while keeping the upload small enough
 * to finish on a phone signal in the field.
 */
const val UPLOAD_LONG_EDGE_PX = 1_024

/** High enough that re-compression does not cost the classifier detail it needs. */
const val UPLOAD_JPEG_QUALITY = 85
