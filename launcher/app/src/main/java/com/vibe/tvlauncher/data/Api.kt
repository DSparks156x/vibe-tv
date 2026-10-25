package com.vibe.tvlauncher.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A title from Cinemeta or the Nuvio library. */
data class Meta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val genres: List<String>,
    val year: String?,
    val rating: String?,
    val description: String?,
    val runtime: String?,
    val trailerYt: String?,
) {
    val isImdb get() = id.startsWith("tt")

    fun backdrop(size: String = "medium"): String? =
        if (isImdb) "https://images.metahub.space/background/$size/$id/img" else background ?: poster

    fun logoUrl(): String? = logo ?: if (isImdb) "https://images.metahub.space/logo/medium/$id/img" else null

    fun posterUrl(): String? = poster ?: if (isImdb) "https://images.metahub.space/poster/medium/$id/img" else null
}

/** An entry in Nuvio's "Continue watching". */
data class ContinueItem(
    val meta: Meta,
    val videoId: String?,
    val progress: Float,
    val season: Int?,
    val episode: Int?,
    val lastWatched: Long,
    val episodeTitle: String? = null,
    /** The next episode to start, rather than one already in progress. */
    val nextUp: Boolean = false,
    /** Episode still, when Nuvio knows it. */
    val thumbnail: String? = null,
    val remainingMs: Long? = null,
) {
    val subtitle: String?
        get() {
            val ep = if (season != null && episode != null) "S$season E$episode" else null
            val left = remainingMs?.takeIf { it > 60_000 && !nextUp }?.let { "${it / 60_000} min left" }
            return listOfNotNull(ep, if (nextUp) "Next episode" else left).joinToString(" · ").ifEmpty { null }
        }
}

private fun enc(s: String) = Uri.encode(s)

class ApiException(message: String) : IOException(message)

object Http {
    const val UA = "VibeTV/0.1 (Android TV)"

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode !in 200..299) throw ApiException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun postJson(url: String, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (text.isEmpty()) throw ApiException("HTTP ${c.responseCode} for $url")
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}

object Cinemeta {
    private const val BASE = "https://v3-cinemeta.strem.io"

    fun catalogRaw(type: String, id: String): String = Http.get("$BASE/catalog/$type/$id.json")

    /** Aired episode of a series (season 0 specials excluded). */
    data class Episode(val id: String, val season: Int, val episode: Int, val title: String?, val thumbnail: String?, val released: Long)

    fun episodes(seriesId: String): List<Episode> = runCatching {
        val meta = JSONObject(Http.get("$BASE/meta/series/$seriesId.json")).optJSONObject("meta") ?: return emptyList()
        val videos = meta.optJSONArray("videos") ?: return emptyList()
        (0 until videos.length()).mapNotNull { i ->
            val v = videos.optJSONObject(i) ?: return@mapNotNull null
            val season = v.optInt("season", 0)
            val ep = v.optInt("episode", v.optInt("number", 0))
            val released = v.optStringOrNull("released")
                ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return@mapNotNull null
            if (season <= 0 || ep <= 0) return@mapNotNull null
            Episode(
                id = v.optStringOrNull("id") ?: "$seriesId:$season:$ep",
                season = season,
                episode = ep,
                title = v.optStringOrNull("name") ?: v.optStringOrNull("title"),
                thumbnail = v.optStringOrNull("thumbnail"),
                released = released,
            )
        }
    }.getOrDefault(emptyList())

    /** Full metadata for one title (used for genres of Continue watching items). */
    fun meta(type: String, id: String): Meta? =
        runCatching { parseMeta(JSONObject(Http.get("$BASE/meta/$type/$id.json")).optJSONObject("meta")) }.getOrNull()

    /** TMDB id from Cinemeta's meta, when it has one ("moviedb_id"). */
    fun tmdbId(type: String, id: String): Int? = runCatching {
        JSONObject(Http.get("$BASE/meta/$type/$id.json")).optJSONObject("meta")?.optInt("moviedb_id", 0)?.takeIf { it > 0 }
    }.getOrNull()

    fun parseCatalog(raw: String): List<Meta> {
        val arr = JSONObject(raw).optJSONArray("metas") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> parseMeta(arr.optJSONObject(i)) }
    }

    fun parseMeta(o: JSONObject?): Meta? {
        if (o == null) return null
        val id = o.optString("id").ifEmpty { o.optString("imdb_id") }
        val name = o.optString("name")
        if (id.isEmpty() || name.isEmpty()) return null
        val genres = (o.optJSONArray("genres") ?: o.optJSONArray("genre"))?.strings() ?: emptyList()
        val trailer = o.optJSONArray("trailerStreams")?.optJSONObject(0)?.optString("ytId")
            ?: o.optJSONArray("trailers")?.optJSONObject(0)?.optString("source")
        return Meta(
            id = id,
            type = o.optString("type", "movie"),
            name = name,
            poster = o.optStringOrNull("poster"),
            background = o.optStringOrNull("background"),
            logo = o.optStringOrNull("logo"),
            genres = genres,
            year = o.optStringOrNull("releaseInfo") ?: o.optStringOrNull("year"),
            rating = o.optStringOrNull("imdbRating"),
            description = o.optStringOrNull("description"),
            runtime = o.optStringOrNull("runtime"),
            trailerYt = trailer?.ifEmpty { null },
        )
    }
}

private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).ifEmpty { null } }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }
