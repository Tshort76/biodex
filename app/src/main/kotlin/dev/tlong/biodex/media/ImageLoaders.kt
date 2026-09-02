package dev.tlong.biodex.media

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import java.io.File
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** ARCHITECTURE.md 5.3: `cacheDir/coil_images`, 250 MB. */
const val IMAGE_CACHE_DIR = "coil_images"
const val IMAGE_CACHE_BYTES = 250L * 1024 * 1024

/**
 * The app's one Coil `ImageLoader` (ARCHITECTURE.md 5.3). Two things here are load-bearing:
 *
 * - **The disk cache is what makes S02 true.** Without it Coil keeps only a memory cache, and
 *   a reference image would be re-fetched on every cold start — so a previously viewed entry
 *   would be blank in the field.
 * - **The OkHttp fetcher must be registered explicitly.** Coil 3 ships no network fetcher in
 *   its core artifact; remote URLs silently fail without this (1.2's note on `coil-network-okhttp`).
 *   Registering the app's shared client also gets Wikimedia the descriptive User-Agent it
 *   requires, via the interceptor `AppContainer` installs.
 */
fun buildImageLoader(
    context: Context,
    client: OkHttpClient,
    cacheDirectory: File = File(context.cacheDir, IMAGE_CACHE_DIR),
): ImageLoader = ImageLoader.Builder(context)
    .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
    .diskCache {
        DiskCache.Builder()
            .directory(cacheDirectory.toOkioPath())
            .maxSizeBytes(IMAGE_CACHE_BYTES)
            .build()
    }
    .crossfade(true)
    .build()
