package dev.tlong.animaldex.data.photo

import java.io.File

/** ARCHITECTURE.md 4.3: 640 px on the long edge, JPEG quality 85. */
const val THUMBNAIL_LONG_EDGE_PX = 640
const val THUMBNAIL_QUALITY = 85

/**
 * Capture paths are stored **relative** to `filesDir` (3.1), so a backup restored under a
 * different absolute `filesDir` still resolves. Keying by capture id is what makes re-linking
 * overwrite in place rather than leak a file (4.3).
 */
fun thumbnailRelativePath(captureId: String): String = "thumbnails/$captureId.jpg"

/** S03's local copy, same keying. */
fun localCopyRelativePath(captureId: String): String = "photos/$captureId.jpg"

/**
 * The single relative-to-absolute resolver. Everything that renders an app-owned file — grid
 * cell, detail strip, photo viewer, the reveal — goes through here rather than building
 * `File(filesDir, …)` in a composable.
 */
fun ownedFile(filesDir: File, relativePath: String): File = File(filesDir, relativePath)

/**
 * Coil 3 loads app-owned files by `file://` URI on Android. Returning the string (rather than
 * a `File`) keeps the call sites free of a Coil-version-specific model type.
 */
fun ownedFileModel(filesDir: File, relativePath: String?): String? =
    relativePath?.let { "file://" + ownedFile(filesDir, it).absolutePath }
