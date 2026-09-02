package dev.tlong.animaldex.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.File
import okhttp3.OkHttpClient

/** ARCHITECTURE.md 5.3: `cacheDir/media_audio`, 200 MB LRU, `StandaloneDatabaseProvider`. */
const val AUDIO_CACHE_DIR = "media_audio"
const val AUDIO_CACHE_BYTES = 200L * 1024 * 1024

/** 5.3: `cacheDir/coil_images`, 250 MB. */
const val IMAGE_CACHE_DIR = "coil_images"
const val IMAGE_CACHE_BYTES = 250L * 1024 * 1024

/**
 * A `SimpleCache` locks its directory for the life of the process and **throws** if a second
 * instance opens the same folder, so exactly one of these may exist — `AppContainer` holds it
 * lazily and nothing else constructs one. (The instrumented test releases the one it makes.)
 */
@OptIn(UnstableApi::class)
fun buildAudioCache(context: Context, directory: File = File(context.cacheDir, AUDIO_CACHE_DIR)) =
    SimpleCache(
        directory,
        LeastRecentlyUsedCacheEvictor(AUDIO_CACHE_BYTES),
        StandaloneDatabaseProvider(context),
    )

/**
 * Cache-on-first-play (D4): reads come from the cache when they can and stream through it
 * otherwise, writing as they go. `FLAG_IGNORE_CACHE_ON_ERROR` is what keeps a half-written
 * entry from a dropped connection out of the way of the next attempt.
 */
@OptIn(UnstableApi::class)
fun callDataSourceFactory(cache: SimpleCache, client: OkHttpClient): DataSource.Factory =
    CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
