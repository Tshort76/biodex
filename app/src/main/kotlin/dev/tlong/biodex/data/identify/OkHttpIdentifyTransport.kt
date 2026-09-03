package dev.tlong.biodex.data.identify

import dev.tlong.biodex.data.net.FetchResult
import java.io.IOException
import kotlin.coroutines.suspendCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The socket half of identification — the only place in the app that writes bytes to a network
 * (M36), and deliberately the thinnest thing that can be.
 *
 * It shares `AppContainer`'s single `OkHttpClient`, so the upload carries the same descriptive
 * User-Agent every other call does. The response is *not* cached in any useful sense: OkHttp
 * does not cache POSTs, which is the behaviour §5.2 rule 9 wants anyway — pressing Identify
 * again re-uploads rather than replaying a stale answer.
 */
class OkHttpIdentifyTransport(private val client: OkHttpClient) : IdentifyTransport {

    override suspend fun post(url: String, image: UploadImage): FetchResult =
        suspendCoroutine { continuation ->
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(ORGAN_PART_NAME, ORGAN_AUTO)
                .addFormDataPart(
                    IMAGE_PART_NAME,
                    image.fileName,
                    image.bytes.toRequestBody(image.mimeType.toMediaType()),
                )
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(body)
                .build()
            client.newCall(request).enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWith(
                            Result.success(
                                FetchResult.Failed(e.message ?: e.javaClass.simpleName),
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = response.use {
                            classifyIdentifyResponse(it.code) {
                                runCatching { it.body?.string().orEmpty() }.getOrNull()
                            }
                        }
                        continuation.resumeWith(Result.success(result))
                    }
                },
            )
        }
}

/**
 * The status-code mapping, pulled out so the JVM suite pins it without a socket.
 *
 * The three that matter are not interchangeable, and each is a different sentence to the user
 * (M38): a **404** is Pl@ntNet saying it recognised nothing in the photo, which is an ordinary
 * answer; a **429** is the daily quota, which is the app failing to ask and must say so by
 * name; a **401** is a key the service rejected, which sends the user to Settings rather than
 * to "try again".
 *
 * The reasons are written for the user rather than for a log, because they are rendered
 * verbatim under the button.
 */
internal fun classifyIdentifyResponse(code: Int, body: () -> String?): FetchResult = when {
    code == 404 -> FetchResult.NotFound
    code == 401 || code == 403 ->
        FetchResult.Failed("Pl@ntNet rejected the key — check it in Settings")

    code == 429 -> FetchResult.Failed("Pl@ntNet's daily quota is used up — try again tomorrow")
    code == 413 -> FetchResult.Failed("That photo was too large for Pl@ntNet")
    code in 500..599 -> FetchResult.Failed("Pl@ntNet is not answering — try again")
    code !in 200..299 -> FetchResult.Failed("Could not reach Pl@ntNet (HTTP $code)")
    else -> body()?.let(FetchResult::Body) ?: FetchResult.Failed("unreadable body")
}
