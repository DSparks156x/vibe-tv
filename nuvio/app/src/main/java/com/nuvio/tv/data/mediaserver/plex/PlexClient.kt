package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.buildUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Talks to plex.tv and Plex Media Servers with the X-Plex-* identity headers Plex expects. */
internal class PlexClient(
    private val http: OkHttpClient,
    private val identity: ServerClientIdentity
) {
    private val probeHttp = http.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    val clientId: String
        get() = identity.deviceId()

    /** Query parameters that identify this player; media URLs carry them because the player sends no headers. */
    fun identityQuery(token: String?): Map<String, String?> = mapOf(
        "X-Plex-Client-Identifier" to clientId,
        "X-Plex-Product" to PRODUCT,
        "X-Plex-Platform" to PLATFORM,
        "X-Plex-Device" to identity.device,
        "X-Plex-Token" to token
    )

    suspend fun <T> get(
        baseUrl: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        token: String?,
        query: Map<String, String?> = emptyMap()
    ): T = json.decodeFromString(deserializer, execute("GET", baseUrl, path, token, query))

    suspend fun execute(
        method: String,
        baseUrl: String,
        path: String,
        token: String?,
        query: Map<String, String?> = emptyMap(),
        probe: Boolean = false
    ): String {
        val request = try {
            Request.Builder()
                .url(buildUrl(baseUrl, path, query))
                .headers(headers(token))
                .method(method, if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null)
                .build()
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.NOT_FOUND)
        }
        val (status, body) = try {
            send(if (probe) probeHttp else http, request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE)
        }
        val failure = when {
            status in 200..299 -> return body
            status == 401 -> ServerFailure.AUTH_REQUIRED
            status == 403 -> ServerFailure.FORBIDDEN
            status == 404 -> ServerFailure.NOT_FOUND
            status in 500..599 -> ServerFailure.UNREACHABLE
            else -> ServerFailure.FAILED
        }
        throw ServerException(failure, "HTTP $status")
    }

    private fun headers(token: String?): okhttp3.Headers = okhttp3.Headers.Builder().apply {
        add("Accept", "application/json")
        add("X-Plex-Client-Identifier", clientId)
        add("X-Plex-Product", PRODUCT)
        add("X-Plex-Version", identity.version.headerSafe())
        add("X-Plex-Platform", PLATFORM)
        add("X-Plex-Device", identity.device.headerSafe())
        add("X-Plex-Device-Name", identity.device.headerSafe())
        if (token != null) add("X-Plex-Token", token)
    }.build()

    private suspend fun send(client: OkHttpClient, request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = response.body?.let { source ->
                                if (source.contentLength() > MAX_BODY_BYTES) throw IOException("Response exceeds size limit")
                                source.string()
                            }.orEmpty()
                            if (continuation.isActive) continuation.resume(response.code to body)
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    companion object {
        const val PLEX_TV = "https://plex.tv"
        const val PRODUCT = "Nuvio"
        private const val PLATFORM = "Android"
        private const val PROBE_TIMEOUT_SECONDS = 4L
        private const val MAX_BODY_BYTES = 16L * 1024L * 1024L
    }
}

private fun String.headerSafe(): String = filter { it in ' '..'~' }.ifBlank { "Android TV" }
