package dev.tlong.animaldex.data.net

import java.io.IOException
import kotlin.coroutines.suspendCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The one platform seam of the network layer (ARCHITECTURE.md 5.2). Everything above it —
 * URL construction, JSON parsing, the fallback chains, the merge rules — is ordinary Kotlin
 * over strings, so the JVM suite drives all three clients against real captured payloads and
 * only the socket itself goes untested. Same split slice 5 used for `PhotoGateway`.
 */
fun interface JsonFetcher {
    suspend fun get(url: String): FetchResult
}

sealed interface FetchResult {
    /** A 2xx response with its body. */
    data class Body(val text: String) : FetchResult

    /** A 404, or an endpoint's own "nothing here" status. Not an error. */
    data object NotFound : FetchResult

    /** Anything else: no network, a timeout, a 5xx, an unparseable body. */
    data class Failed(val reason: String) : FetchResult
}

/**
 * The real fetcher. It shares [AppContainer][dev.tlong.animaldex.AppContainer]'s single
 * `OkHttpClient`, so every API call carries the descriptive User-Agent Wikimedia requires and
 * lands in the 20 MB response cache that makes a backfill retry cheap (5.2).
 */
class OkHttpJsonFetcher(private val client: OkHttpClient) : JsonFetcher {

    override suspend fun get(url: String): FetchResult = suspendCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(
                        Result.success(FetchResult.Failed(e.message ?: e.javaClass.simpleName)),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        when {
                            it.code == 404 -> FetchResult.NotFound
                            !it.isSuccessful -> FetchResult.Failed("HTTP ${it.code}")
                            else -> runCatching { FetchResult.Body(it.body?.string().orEmpty()) }
                                .getOrElse { error ->
                                    FetchResult.Failed(error.message ?: "unreadable body")
                                }
                        }
                    }
                    continuation.resumeWith(Result.success(result))
                }
            },
        )
    }
}
