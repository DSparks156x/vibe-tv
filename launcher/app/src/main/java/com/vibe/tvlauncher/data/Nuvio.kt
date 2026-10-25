package com.vibe.tvlauncher.data

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log

/** A catalog from one of the user's installed Nuvio add-ons. */
data class AddonCatalog(
    val addon: String,
    val baseUrl: String,
    val type: String,
    val id: String,
    val name: String,
    val showInHome: Boolean,
) {
    /** Stremio add-on protocol: `<base>/catalog/<type>/<id>.json`. */
    val url: String
        get() = baseUrl.removeSuffix("/manifest.json").trimEnd('/') + "/catalog/$type/${Uri.encode(id)}.json"

    val isCinemeta: Boolean get() = "cinemeta" in baseUrl
}

/** A Nuvio profile, for the launcher's avatar and profile switcher. */
data class NuvioProfile(
    val name: String,
    val avatarUrl: String?,
    val colorHex: String?,
    val id: String? = null,
    val active: Boolean = true,
)

/**
 * Reads the Vibe Nuvio fork (com.vibe.nuvio) through its signature-protected provider
 * and builds the intents that open titles in it.
 */
object Nuvio {
    const val PACKAGE = "com.vibe.nuvio"
    private const val ACTIVITY = "com.nuvio.tv.MainActivity"
    private const val AUTHORITY = "$PACKAGE.launcher"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun continueWatching(context: Context): List<ContinueItem> = query(context, "continue_watching") { c ->
        val meta = Meta(
            id = c.str("content_id") ?: return@query null,
            type = c.str("content_type") ?: "movie",
            name = c.str("name") ?: return@query null,
            poster = c.str("poster"),
            background = c.str("backdrop"),
            logo = c.str("logo"),
            genres = emptyList(),
            year = null,
            rating = null,
            description = null,
            runtime = null,
            trailerYt = null,
        )
        ContinueItem(
            meta = meta,
            videoId = c.str("video_id"),
            progress = c.getFloat(c.getColumnIndexOrThrow("progress")),
            season = c.int("season"),
            episode = c.int("episode"),
            lastWatched = c.getLong(c.getColumnIndexOrThrow("last_watched")),
            episodeTitle = c.str("episode_title"),
            // Optional columns from newer fork builds.
            nextUp = c.str("kind") == "next_up",
            thumbnail = c.str("thumbnail"),
            remainingMs = c.long("remaining_ms"),
        )
    }

    fun library(context: Context): List<Meta> = query(context, "library") { c ->
        Meta(
            id = c.str("id") ?: return@query null,
            type = c.str("type") ?: "movie",
            name = c.str("name") ?: return@query null,
            poster = c.str("poster"),
            background = c.str("background"),
            logo = c.str("logo"),
            genres = c.str("genres")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            year = c.str("release_info"),
            rating = c.str("rating"),
            description = c.str("description"),
            runtime = null,
            trailerYt = null,
        )
    }

    fun profile(context: Context): NuvioProfile? = query(context, "profile") { c ->
        NuvioProfile(c.str("name") ?: "", c.str("avatar_url"), c.str("avatar_color"))
    }.firstOrNull()

    /** All profiles (newer fork builds); empty if unsupported. */
    fun profiles(context: Context): List<NuvioProfile> = query(context, "profiles") { c ->
        NuvioProfile(
            name = c.str("name") ?: "",
            avatarUrl = c.str("avatar_url"),
            colorHex = c.str("avatar_color"),
            id = c.str("id"),
            active = c.int("is_active") == 1,
        )
    }

    // ---- Write actions (ContentProvider.call on the fork, same signature permission) ----

    fun libraryContains(context: Context, meta: Meta): Boolean? =
        call(context, "library_contains", null, metaExtras(meta))?.takeIf { it.getBoolean("ok") }
            ?.getBoolean("in_library")

    /** Returns the new membership, or null if the fork doesn't support it. */
    fun toggleLibrary(context: Context, meta: Meta): Boolean? =
        call(context, "library_toggle", null, metaExtras(meta).apply {
            putString("name", meta.name)
            meta.poster?.let { putString("poster", it) }
            meta.background?.let { putString("background", it) }
            meta.logo?.let { putString("logo", it) }
        })?.takeIf { it.getBoolean("ok") }?.getBoolean("in_library")

    fun markWatched(context: Context, item: ContinueItem): Boolean =
        call(context, "mark_watched", null, metaExtras(item.meta).apply {
            item.videoId?.let { putString("video_id", it) }
            item.season?.let { putInt("season", it) }
            item.episode?.let { putInt("episode", it) }
        })?.getBoolean("ok") == true

    fun removeProgress(context: Context, item: ContinueItem): Boolean =
        call(context, "remove_progress", null, metaExtras(item.meta).apply {
            item.season?.let { putInt("season", it) }
            item.episode?.let { putInt("episode", it) }
        })?.getBoolean("ok") == true

    fun setProfile(context: Context, id: String): Boolean =
        call(context, "set_profile", id, null)?.getBoolean("ok") == true

    private fun metaExtras(meta: Meta) = android.os.Bundle().apply {
        putString("id", meta.id)
        putString("type", meta.type)
    }

    private fun call(context: Context, method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? = try {
        context.contentResolver.call(Uri.parse("content://$AUTHORITY"), method, arg, extras)
    } catch (e: Exception) {
        Log.w("VibeTV", "Nuvio call $method failed: ${e.message}")
        null
    }

    /** Catalogs that can be shown as home rows (no required extras like search queries). */
    fun addonCatalogs(context: Context): List<AddonCatalog> = query(context, "addons") { c ->
        val base = c.str("base_url") ?: return@query null
        val addon = c.str("name") ?: ""
        val json = runCatching { org.json.JSONArray(c.str("catalogs") ?: "[]") }.getOrNull() ?: return@query null
        (0 until json.length()).mapNotNull { i ->
            val o = json.optJSONObject(i) ?: return@mapNotNull null
            if ((o.optJSONArray("extraRequired")?.length() ?: 0) > 0) return@mapNotNull null
            AddonCatalog(
                addon = addon,
                baseUrl = base,
                type = o.optString("type"),
                id = o.optString("id"),
                name = o.optString("name").ifEmpty { o.optString("id") },
                showInHome = o.optBoolean("showInHome"),
            )
        }
    }.flatten()

    /** URI the Nuvio provider notifies on when watch progress or library changes. */
    val changesUri: Uri = Uri.parse("content://$AUTHORITY/")

    /** Search screen (deep link added in the fork; older builds just open Nuvio). */
    fun searchIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("nuvio://search")).setPackage(PACKAGE)

    /** Title page in Nuvio. */
    fun detailIntent(meta: Meta): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("nuvio://detail/${meta.type}/${Uri.encode(meta.id)}"))
            .setPackage(PACKAGE)

    /** Resume straight into stream selection / auto-play, same intent Nuvio's own TV channel uses. */
    fun resumeIntent(item: ContinueItem): Intent {
        val m = item.meta
        return Intent(Intent.ACTION_VIEW).apply {
            setClassName(PACKAGE, ACTIVITY)
            putExtra("contentId", m.id)
            putExtra("contentType", m.type)
            putExtra("videoId", item.videoId ?: m.id)
            putExtra("name", m.name)
            putExtra("poster", m.poster)
            putExtra("backdrop", m.background)
            putExtra("logo", m.logo)
            item.season?.let { putExtra("season", it) }
            item.episode?.let { putExtra("episode", it) }
            item.episodeTitle?.let { putExtra("episodeTitle", it) }
            putExtra("launchMode", "stream")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    fun mainIntent(): Intent = Intent(Intent.ACTION_MAIN).setClassName(PACKAGE, ACTIVITY)

    private fun <T> query(context: Context, path: String, row: (Cursor) -> T?): List<T> = try {
        context.contentResolver.query(Uri.parse("content://$AUTHORITY/$path"), null, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) row(c)?.let(::add) }
        } ?: emptyList()
    } catch (e: Exception) {
        // Not installed, or signed with a different key.
        Log.w("VibeTV", "Nuvio $path unavailable: ${e.message}")
        emptyList()
    }

    private fun Cursor.str(col: String): String? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getString(i)?.ifEmpty { null }
    }

    private fun Cursor.long(col: String): Long? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }

    private fun Cursor.int(col: String): Int? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else getInt(i)
    }
}
