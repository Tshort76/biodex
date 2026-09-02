package dev.tlong.biodex.media

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S02/D4's cache layer, on a device. These have never run — no phone has been connected to
 * this project — but they are the checks that will answer "does a played call survive going
 * offline" without a human toggling airplane mode.
 *
 * Every test uses its **own** directory under the instrumentation cache: a `SimpleCache` locks
 * its folder for the process, so sharing one with the app's real cache (or between tests
 * without releasing) fails on the lock rather than on the behaviour under test.
 */
@RunWith(AndroidJUnit4::class)
class MediaCacheTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "test_media_audio_${System.nanoTime()}")
    private var cache: SimpleCache? = null

    @After
    fun tearDown() {
        cache?.release()
        directory.deleteRecursively()
    }

    private fun open(): SimpleCache = buildAudioCache(context, directory).also { cache = it }

    private fun write(cache: SimpleCache, key: String, bytes: ByteArray) {
        val sink = CacheDataSink(cache, bytes.size.toLong())
        sink.open(
            DataSpec.Builder()
                .setUri("https://xeno-canto.org/$key/download")
                .setKey(key)
                .setLength(bytes.size.toLong())
                .build(),
        )
        sink.write(bytes, 0, bytes.size)
        sink.close()
    }

    @Test
    fun audioCacheWritesUnderTheCacheDirectory() {
        val cache = open()
        write(cache, "call-1", ByteArray(2048) { 7 })

        assertTrue(directory.exists())
        assertEquals(2048L, cache.getCachedBytes("call-1", 0, 2048))
    }

    /** D4's whole promise: the second play is local. Closing stands in for a process restart. */
    @Test
    fun cachedAudioSurvivesTheCacheBeingClosedAndReopened() {
        val first = open()
        write(first, "call-2", ByteArray(4096) { 3 })
        first.release()
        cache = null

        val second = open()
        assertEquals(4096L, second.getCachedBytes("call-2", 0, 4096))
    }

    /**
     * The offline half of S02, with no network involved at all: bytes already in the cache are
     * served by the same factory ExoPlayer plays through. `xeno-canto.invalid` cannot resolve,
     * so a read that succeeds can only have come from the cache.
     */
    @Test
    fun aCachedCallIsServedWithoutTouchingTheNetwork() {
        val cache = open()
        val key = "call-3"
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val uri = "https://xeno-canto.invalid/$key/download"
        val sink = CacheDataSink(cache, bytes.size.toLong())
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setKey(key)
            .setLength(bytes.size.toLong())
            .build()
        sink.open(spec)
        sink.write(bytes, 0, bytes.size)
        sink.close()

        val source = callDataSourceFactory(cache, OkHttpClient()).createDataSource()
        source.open(spec)
        val read = ByteArray(bytes.size)
        var offset = 0
        while (offset < read.size) {
            val n = source.read(read, offset, read.size - offset)
            assertTrue("stream ended early at $offset", n > 0)
            offset += n
        }
        source.close()
        assertArrayEquals(bytes, read)
    }

    /** S02 for images: without a disk cache a viewed entry is blank on the next cold start. */
    @Test
    fun theImageLoaderHasTheDiskCacheOfSectionFivePointThree() {
        val loaderDirectory = File(context.cacheDir, "test_$IMAGE_CACHE_DIR")
        try {
            val loader = buildImageLoader(context, OkHttpClient(), loaderDirectory)
            val disk = requireNotNull(loader.diskCache) { "no disk cache configured" }
            assertEquals(IMAGE_CACHE_BYTES, disk.maxSize)
            assertEquals(loaderDirectory.absolutePath, disk.directory.toFile().absolutePath)
        } finally {
            loaderDirectory.deleteRecursively()
        }
    }
}
