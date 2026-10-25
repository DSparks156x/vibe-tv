package com.nuvio.tv.core.vibe

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.BuildConfig

/**
 * Vibe fork: service keys and URLs that upstream only sets at build time (CI secrets), made
 * settable at runtime so a release build needs no local.properties. The Vibe launcher sets
 * them through [VibeLauncherProvider] ("get_settings" / "set_settings").
 *
 * Each value is the stored override, else the BuildConfig default. Most are read when Nuvio's
 * network clients are created, so the provider restarts Nuvio's process after a change.
 * Device-wide (not per profile), stored in SharedPreferences so it is readable before Hilt
 * builds anything ([init] runs in Application.attachBaseContext).
 */
object RuntimeConfig {

    enum class Type(val wire: String) { TEXT("text"), SECRET("secret"), BOOL("bool"), URL("url") }

    data class Field(
        val key: String,
        val label: String,
        val help: String,
        val type: Type,
        val group: String,
        val buildDefault: () -> String
    )

    /** Whitelist, in display order. The backend URL is handled separately (server discovery). */
    val fields: List<Field> = listOf(
        Field("tmdb_api_key", "TMDB API key", "Free at themoviedb.org > Settings > API (v3 key). Better metadata, collections, trailers.",
            Type.SECRET, "Metadata") { BuildConfig.TMDB_API_KEY },
        Field("mdblist_client_id", "MDBList client ID", "From mdblist.com > Preferences > API. Enables MDBList ratings and lists.",
            Type.TEXT, "Metadata") { BuildConfig.MDBLIST_CLIENT_ID },
        Field("trakt_client_id", "Trakt client ID", "Create an app at trakt.tv/oauth/applications (redirect urn:ietf:wg:oauth:2.0:oob).",
            Type.TEXT, "Trakt") { BuildConfig.TRAKT_CLIENT_ID },
        Field("trakt_client_secret", "Trakt client secret", "From the same Trakt app page.",
            Type.SECRET, "Trakt") { BuildConfig.TRAKT_CLIENT_SECRET },
        Field("simkl_client_id", "Simkl client ID", "Create an app at simkl.com/settings/developer.",
            Type.TEXT, "Simkl") { BuildConfig.SIMKL_CLIENT_ID },
        Field("premiumize_client_id", "Premiumize client ID", "Premiumize OAuth client ID (only needed for Premiumize device login).",
            Type.TEXT, "Debrid") { BuildConfig.PREMIUMIZE_CLIENT_ID },
        Field("introdb_api_url", "IntroDB API URL", "Skip intro/recap/credits data. Default is the public api.introdb.app.",
            Type.URL, "Services") { BuildConfig.INTRODB_API_URL },
        Field("trailer_api_url", "Trailer API URL", "Optional trailer resolver service (official Nuvio builds use a private one).",
            Type.URL, "Services") { BuildConfig.TRAILER_API_URL },
        Field("imdb_ratings_api_url", "IMDb ratings API URL", "Optional IMDb ratings service.",
            Type.URL, "Services") { BuildConfig.IMDB_RATINGS_API_BASE_URL },
        Field("imdb_tapframe_api_url", "IMDb Tapframe API URL", "Optional IMDb metadata service.",
            Type.URL, "Services") { BuildConfig.IMDB_TAPFRAME_API_BASE_URL }
    )

    private const val PREFS = "vibe_runtime_config"
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun field(key: String): Field? = fields.firstOrNull { it.key == key }

    fun override(key: String): String? = prefs?.getString(key, null)?.takeIf { it.isNotBlank() }

    fun value(key: String): String = override(key) ?: field(key)?.buildDefault?.invoke().orEmpty()

    /** Stores [value] as an override; blank clears it back to the build default. */
    fun setOverride(key: String, value: String): Boolean {
        val p = prefs ?: return false
        return p.edit().apply {
            if (value.isBlank()) remove(key) else putString(key, value.trim())
        }.commit()
    }

    val tmdbApiKey: String get() = value("tmdb_api_key")
    val mdblistClientId: String get() = value("mdblist_client_id")
    val traktClientId: String get() = value("trakt_client_id")
    val traktClientSecret: String get() = value("trakt_client_secret")
    val simklClientId: String get() = value("simkl_client_id")
    val premiumizeClientId: String get() = value("premiumize_client_id")
    val introDbApiUrl: String get() = value("introdb_api_url")
    val trailerApiUrl: String get() = value("trailer_api_url")
    val imdbRatingsApiBaseUrl: String get() = value("imdb_ratings_api_url")
    val imdbTapframeApiBaseUrl: String get() = value("imdb_tapframe_api_url")
}
