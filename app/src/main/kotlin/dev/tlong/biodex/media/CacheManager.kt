package dev.tlong.biodex.media

import android.content.Context
import coil3.ImageLoader
import java.io.File

/**
 * Cache management for the Settings screen (ARCHITECTURE.md 5.3): the reference caches'
 * sizes, and one button that empties the one that holds media bytes.
 *
 * **Clearing never touches thumbnails or local copies.** Those live in `filesDir`, not
 * `cacheDir`, and they are the app's own permanent artifacts (4.3) — the only rendering of
 * a capture the app is guaranteed to keep. Everything this class can delete is
 * re-downloadable.
 */
class CacheManager(
    private val context: Context,
    private val imageLoader: () -> ImageLoader,
) {

    fun sizes(): CacheSizes = CacheSizes(
        imageBytes = runCatching { imageLoader().diskCache?.size ?: 0L }.getOrDefault(0L),
        httpBytes = directorySize(File(context.cacheDir, HTTP_CACHE_DIR)),
    )

    /** S02's cache, re-fillable from the network. Returns the bytes reclaimed. */
    fun clearReferenceCaches(): Long {
        val before = sizes()
        runCatching { imageLoader().diskCache?.clear() }
        return before.imageBytes
    }
}

data class CacheSizes(
    val imageBytes: Long,
    val httpBytes: Long,
) {
    val totalBytes: Long get() = imageBytes + httpBytes
}

/** 5.2's OkHttp response cache, named here so Settings can report it. */
const val HTTP_CACHE_DIR = "http"

private fun directorySize(dir: File): Long =
    if (!dir.isDirectory) 0L else dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

/** `12.4 MB` — the one piece of cache reporting worth a unit test. */
fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 ->
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
