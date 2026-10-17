/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Ported from SimpMusic (https://github.com/maxrave-dev/SimpMusic),
 * core/service/kotlinYtmusicScraper extractor package — GPL-3.0, © maxrave-dev.
 * Logic kept byte-for-byte; only the package name and imports changed:
 * SimpMusic's Logger -> ArchiveTune's Timber.
 */

package moe.rukamori.archivetune.simpstream.extractor

import moe.rukamori.archivetune.simpstream.ITAG
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.Proxy

internal fun List<Pair<Int, String>>.hasRequiredItags(): Boolean {
    val itags = this.mapTo(HashSet()) { it.first }
    val hasAudio = ITAG.AUDIO.any { it in itags }
    val hasVideo = ITAG.VIDEO.any { it in itags }
    return hasAudio && hasVideo
}

private val streamHealthCheckClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .build()
}

/**
 * Pick one random URL whose itag belongs to [ITAG.AUDIO] or [ITAG.VIDEO] and HEAD it. Returns true
 * only when the response code is in the 200..299 range. We only health-check those URLs because
 * they are the ones the player will actually use; lower-quality extras can stay unverified.
 */
internal fun List<Pair<Int, String>>.headCheckRandomStream(): Boolean = headCheckRandomStreamResult() == StreamHeadCheck.OK

/** Outcome of a stream health check. Only [REJECTED] says anything about the cipher / signature. */
internal enum class StreamHeadCheck {
    /** 2xx: the URL is playable. */
    OK,

    /** 4xx: the server refused the URL (stale signature, expired, wrong client). */
    REJECTED,

    /** Network error, timeout or 5xx: we learned nothing about the URL itself. */
    UNKNOWN,
}

internal fun List<Pair<Int, String>>.headCheckRandomStreamResult(): StreamHeadCheck {
    val required = ITAG.AUDIO + ITAG.VIDEO
    val candidate = this.filter { it.first in required }.randomOrNull() ?: return StreamHeadCheck.REJECTED
    return try {
        val request =
            okhttp3.Request
                .Builder()
                .head()
                .url(candidate.second)
                .build()
        streamHealthCheckClient.newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> StreamHeadCheck.OK
                in 400..499 -> StreamHeadCheck.REJECTED
                else -> StreamHeadCheck.UNKNOWN
            }
        }
    } catch (e: IOException) {
        StreamHeadCheck.UNKNOWN
    } catch (e: IllegalArgumentException) {
        StreamHeadCheck.REJECTED
    }
}

class BraveNewPipeDownloaderImpl(
    proxy: Proxy?,
) : Downloader() {
    private val client =
        OkHttpClient
            .Builder()
            .proxy(proxy)
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder =
            okhttp3.Request
                .Builder()
                .method(httpMethod, dataToSend?.toRequestBody())
                .url(url)
                .addHeader("User-Agent", moe.rukamori.archivetune.innertube.models.YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()

            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body.string()

        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }
}
