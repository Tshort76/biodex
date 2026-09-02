package dev.tlong.animaldex.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import java.io.File

/**
 * Cache management for the Settings screen (ARCHITECTURE.md 5.3): the three reference
 * caches' sizes, and one button that empties the two that hold media bytes.
 *
 * **Clearing never touches thumbnails or local copies.** Those live in `filesDir`, not
 * `cacheDir`, and they are the app's own permanent artifacts (4.3) — the only rendering of
 * a capture the app is guaranteed to keep. Everything this class can delete is
 * re-downloadable.
 *
 * The audio cache is passed in rather than constructed: a second `SimpleCache` over
 * `media_audio` throws, so the container's single instance is the only one that may exist.
 */
class CacheManager(
    private val context: Context,
    private val imageLoader: () -> ImageLoader,
    private val audioCache: () -> SimpleCache,
) {

    @OptIn(UnstableApi::class)
    fun sizes(): CacheSizes = CacheSizes(
        imageBytes = runCatching { imageLoader().diskCache?.size ?: 0L }.getOrDefault(0L),
        audioBytes = runCatching { audioCache().cacheSpace }.getOrDefault(0L),
        httpBytes = directorySize(File(context.cacheDir, HTTP_CACHE_DIR)),
    )

    /** S02's caches, both re-fillable from the network. Returns the bytes reclaimed. */
    @OptIn(UnstableApi::class)
    fun clearReferenceCaches(): Long {
        val before = sizes()
        runCatching { imageLoader().diskCache?.clear() }
        runCatching {
            val cache = audioCache()
            cache.keys.toList().forEach { key -> cache.removeResource(key) }
        }
        return before.imageBytes + before.audioBytes
    }
}

data class CacheSizes(
    val imageBytes: Long,
    val audioBytes: Long,
    val httpBytes: Long,
) {
    val totalBytes: Long get() = imageBytes + audioBytes + httpBytes
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
