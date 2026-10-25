package com.vibe.tvlauncher.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(
    val key: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    /** Full-bleed square render of an adaptive icon, meant to be clipped to a circle. */
    val round: ImageBitmap?,
    val intent: Intent,
    val favorite: Boolean = false,
    /** Hidden from Your apps and the main Apps grid; listed under "Hidden apps" so it can be restored. */
    val hidden: Boolean = false,
)

object Pkg {
    const val NUVIO = "com.vibe.nuvio"
    const val SETTINGS = "com.android.tv.settings"

    /** YouTube-capable TV apps, preferred in this order (TizenTube, SmartTube, official). */
    val YOUTUBE_APPS = listOf(
        "io.gh.reisxd.tizentube.cobalt",
        "com.teamsmart.videomanager.tv",
        "com.liskovsoft.smarttubetv.beta",
        "com.google.android.youtube.tv",
    )
}

object AppRepo {
    /**
     * First-run favorites, used until the user edits the row (long-press an app).
     * Only installed ones show, so this works on anyone's TV.
     */
    private val defaultFavorites = Pkg.YOUTUBE_APPS + listOf(
        Pkg.NUVIO,
        "com.amazon.amazonvideo.livingroom",
        "com.netflix.ninja",
        "com.disney.disneyplus",
        "com.plexapp.android",
        "org.jellyfin.androidtv",
        "com.spotify.tv.android",
        "org.localsend.localsend_app",
        Pkg.SETTINGS,
    )

    private const val PREFS = "launcher"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_HIDDEN = "hidden_apps"

    /** Vendor apps nobody asked for, hidden until the user un-hides them (e.g. Hisense's camera "AI sense"). */
    private val defaultHidden = setOf("com.vt.tv.ai_sense")

    fun hiddenApps(context: Context): Set<String> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HIDDEN, null)
        return saved?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: defaultHidden
    }

    fun setHidden(context: Context, key: String, hidden: Boolean) {
        val set = hiddenApps(context).toMutableSet().apply { if (hidden) add(key) else remove(key) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HIDDEN, set.joinToString(",")).apply()
    }

    /** Ordered favorite package names (stored once the user customizes them). */
    fun favorites(context: Context): List<String> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAVORITES, null)
        return saved?.split(",")?.filter { it.isNotBlank() } ?: defaultFavorites
    }

    fun saveFavorites(context: Context, keys: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAVORITES, keys.distinct().joinToString(",")).apply()
    }

    /** Installed YouTube app to hand video links to, if any. */
    fun youtubePackage(context: Context): String? =
        Pkg.YOUTUBE_APPS.firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }

    /** Packages that should never show up (launchers, setup stubs). */
    private val hidden = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.spocky.projengmenu",
        "com.vt.tv.customerlauncher",
    )

    /** Installed apps, favorites first (in the user's order), then the rest A-Z. */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val found = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.packageName !in hidden }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val label = ri.loadLabel(pm).toString()
                val banner = runCatching { ai.loadBanner(pm) }.getOrNull()
                val icon = runCatching { ai.loadIcon(pm) }.getOrNull()
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                AppEntry(ai.packageName, label, banner?.toImage(320, 180), icon?.toImage(192, 192), icon?.fullBleed(), intent)
            }
        val settings = found.firstOrNull { it.key == Pkg.SETTINGS } ?: AppEntry(
            key = Pkg.SETTINGS,
            label = "Settings",
            banner = null,
            icon = null,
            round = null,
            intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val all = (found.filter { it.key != Pkg.SETTINGS } + settings)
        val favorites = favorites(context)
        val hidden = hiddenApps(context)
        val favs = favorites.mapNotNull { f -> all.firstOrNull { it.key == f && it.key !in hidden } }.map { it.copy(favorite = true) }
        val rest = all.filter { it.key !in favorites && it.key !in hidden }.sortedBy { it.label.lowercase() }
        val hiddenApps = all.filter { it.key in hidden }.sortedBy { it.label.lowercase() }.map { it.copy(hidden = true) }
        return favs + rest + hiddenApps
    }

    /** Draws an adaptive icon's layers unmasked so the 72dp safe zone fills the bitmap. */
    private fun Drawable.fullBleed(size: Int = 192): ImageBitmap? {
        if (this !is AdaptiveIconDrawable) return null
        return runCatching {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val inset = size / 4
            listOfNotNull(background, foreground).forEach { layer ->
                layer.setBounds(-inset, -inset, size + inset, size + inset)
                layer.draw(canvas)
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }

    private fun Drawable.toImage(defW: Int, defH: Int): ImageBitmap? = runCatching {
        val w = if (intrinsicWidth > 0) intrinsicWidth.coerceAtMost(defW * 2) else defW
        val h = if (intrinsicHeight > 0) intrinsicHeight.coerceAtMost(defH * 2) else defH
        toBitmap(w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}
