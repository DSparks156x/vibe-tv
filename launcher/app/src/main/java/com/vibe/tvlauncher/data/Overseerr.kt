package com.vibe.tvlauncher.data

import android.content.Context
import com.vibe.tvlauncher.settings.LauncherPrefs
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Requests through Overseerr or Jellyseerr (same API). Blocking; call off the main thread. */
object Overseerr {
    /** Where a title stands on the server, from mediaInfo.status. */
    enum class Status { None, Requested, Partial, Available }

    fun configured(c: Context) = LauncherPrefs.overseerrUrl(c) != null && LauncherPrefs.overseerrKey(c) != null

    /** Status for the hold menu, or null when the lookup failed (the menu then just offers "Request"). */
    fun status(c: Context, meta: Meta): Status? = runCatching {
        fetchStatus(c, meta, tmdbId(c, meta) ?: return null)
    }.getOrNull()

    /** Requests the title (all seasons for a show). Returns a message for a toast. */
    fun request(c: Context, meta: Meta): String = try {
        send(c, meta)
    } catch (e: Exception) {
        e.message?.takeIf { e is OverseerrException } ?: "Can't reach Overseerr. Check the server URL."
    }

    private fun send(c: Context, meta: Meta): String {
        val tmdb = tmdbId(c, meta) ?: return "Couldn't find ${meta.name} on Overseerr"
        when (fetchStatus(c, meta, tmdb)) {
            Status.Requested -> return "${meta.name} is already requested"
            Status.Available -> return "${meta.name} is already on the server"
            else -> Unit
        }
        val body = JSONObject().put("mediaType", mediaType(meta)).put("mediaId", tmdb)
        if (meta.type == "series") body.put("seasons", "all")
        val (code, text) = call(c, "POST", "/api/v1/request", body)
        return when {
            code in 200..299 && code != 202 -> "Requested ${meta.name}"
            // 202: nothing left to request (every season is already requested or available).
            code == 202 || code == 409 -> "${meta.name} is already requested"
            else -> serverError(code, text)
        }
    }

    private fun fetchStatus(c: Context, meta: Meta, tmdb: Int): Status? {
        val (code, text) = call(c, "GET", "/api/v1/${mediaType(meta)}/$tmdb")
        return if (code in 200..299) parseStatus(text) else null
    }

    private fun mediaType(meta: Meta) = if (meta.type == "series") "tv" else "movie"

    // ---- TMDB id ----

    /** meta.id -> TMDB id. Ids never change, so this lives for the process. */
    private val tmdbCache = ConcurrentHashMap<String, Int>()

    /** Cinemeta's moviedb_id first, then Overseerr's own search by name and year. */
    private fun tmdbId(c: Context, meta: Meta): Int? {
        tmdbCache[meta.id]?.let { return it }
        val id = meta.id.removePrefix("tmdb:").takeIf { meta.id.startsWith("tmdb:") }?.toIntOrNull()
            ?: (if (meta.isImdb) Cinemeta.tmdbId(meta.type, meta.id) else null)
            ?: searchTmdbId(c, meta)
            ?: return null
        tmdbCache[meta.id] = id
        return id
    }

    private fun searchTmdbId(c: Context, meta: Meta): Int? {
        val q = java.net.URLEncoder.encode(meta.name, "UTF-8").replace("+", "%20")
        val (code, text) = call(c, "GET", "/api/v1/search?query=$q&page=1")
        if (code !in 200..299) throw OverseerrException(serverError(code, text))
        return matchSearch(text, mediaType(meta), meta.name, meta.year)
    }

    // ---- Parsing (pure, unit tested) ----

    /** Picks the search result with the same media type and name, preferring the same year. */
    internal fun matchSearch(json: String, mediaType: String, name: String, year: String?): Int? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        val want = norm(name)
        val wantYear = year?.take(4)
        val hits = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("mediaType") == mediaType && it.optInt("id", 0) > 0 }
            .filter { norm(it.optString("title").ifEmpty { it.optString("name") }) == want }
        val date = { o: JSONObject -> o.optString("releaseDate").ifEmpty { o.optString("firstAirDate") }.take(4) }
        val pick = hits.firstOrNull { wantYear != null && date(it) == wantYear } ?: hits.singleOrNull()
        return pick?.optInt("id")
    }

    /** mediaInfo.status: 2 pending, 3 processing, 4 partially available, 5 available. No mediaInfo = never requested. */
    internal fun parseStatus(json: String): Status = when (JSONObject(json).optJSONObject("mediaInfo")?.optInt("status", 0)) {
        2, 3 -> Status.Requested
        4 -> Status.Partial
        5 -> Status.Available
        else -> Status.None
    }

    internal fun serverError(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
        return when {
            code == 401 -> "Overseerr rejected the API key"
            // 403 is also how Overseerr answers a bad key; quota limits say so in the message.
            code == 403 -> if (msg.contains("quota", ignoreCase = true)) msg else "Overseerr rejected the API key"
            msg.contains("already", ignoreCase = true) -> "Already requested"
            code == 404 -> "Overseerr doesn't know this title"
            else -> "Overseerr error ($code)"
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // ---- HTTP ----

    private class OverseerrException(message: String) : IOException(message)

    /** Returns (status code, body). Throws on network failure or a rejected key, which every caller treats the same. */
    private fun call(c: Context, method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val base = LauncherPrefs.overseerrUrl(c) ?: throw OverseerrException("Overseerr isn't set up")
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.requestMethod = method
        conn.setRequestProperty("User-Agent", Http.UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-Api-Key", LauncherPrefs.overseerrKey(c).orEmpty())
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401 || (code == 403 && method == "GET")) throw OverseerrException(serverError(code, text))
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
