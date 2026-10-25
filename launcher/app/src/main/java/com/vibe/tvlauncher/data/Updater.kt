package com.vibe.tvlauncher.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the launcher from the project's GitHub releases. Only release builds (versionName "v1.2.3")
 * are offered updates; local and CI dev builds never are. Nuvio updates itself the same way.
 */
object Updater {
    private const val TAG = "VibeUpdate"
    private const val LATEST = "https://api.github.com/repos/DSparks156x/vibe-tv/releases/latest"
    private const val ASSET = "vibe-launcher.apk"
    private const val ACTION_STATUS = "com.vibe.tvlauncher.UPDATE_STATUS"

    data class Release(val tag: String, val apkUrl: String)

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"

    /** The latest release if it's newer than this build; blocking. */
    fun check(context: Context): Release? {
        val current = parse(currentVersion(context)) ?: return null
        return try {
            val o = JSONObject(Http.get(LATEST))
            val tag = o.optString("tag_name")
            val latest = parse(tag) ?: return null
            if (compare(latest, current) <= 0) return null
            val assets = o.optJSONArray("assets") ?: return null
            (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == ASSET }
                ?.let { Release(tag, it.getString("browser_download_url")) }
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
            null
        }
    }

    /** "v1.2.3" -> [1, 2, 3]; null for dev builds. */
    fun parse(version: String): List<Int>? {
        if (!version.startsWith("v")) return null
        val parts = version.removePrefix("v").substringBefore('-').split('.')
        return parts.map { it.toIntOrNull() ?: return null }
    }

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** Android's "install unknown apps" switch for the launcher; null if it's already on. */
    fun permissionIntent(context: Context): Intent? =
        if (context.packageManager.canRequestPackageInstalls()) null
        else Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Downloads the APK and hands it to the package installer, which shows Android's confirmation.
     * Blocking; returns an error message, or null once the confirmation is up.
     */
    fun install(context: Context, release: Release): String? = try {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", Http.UA)
            conn.inputStream.use { input ->
                session.openWrite(ASSET, 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(
                context, id, Intent(ACTION_STATUS).setPackage(context.packageName), flags,
            )
            session.commit(status.intentSender)
        }
        null
    } catch (e: Exception) {
        Log.w(TAG, "update install failed", e)
        "Couldn't download the update"
    }

    /** Receives the installer's status; shows its confirmation screen when it asks for one. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // The new launcher starts on its own.
                else -> Log.w(TAG, "install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }
}
