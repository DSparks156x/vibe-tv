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
 * Updates the launcher and Vibe Nuvio from the project's GitHub releases, one Update button for both.
 * Only release builds (versionName "v1.2.3") are offered updates; local and CI dev builds never are.
 */
object Updater {
    private const val TAG = "VibeUpdate"
    private const val LATEST = "https://api.github.com/repos/DSparks156x/vibe-tv/releases/latest"
    private const val LAUNCHER_ASSET = "vibe-launcher.apk"
    private const val ACTION_STATUS = "com.vibe.tvlauncher.UPDATE_STATUS"
    private const val QUEUE = "update_queue"

    /** One app to update: its package and the APK to install. */
    data class App(val pkg: String, val url: String)

    /** A newer release; [apps] are installed in order (Nuvio first, the launcher last since it restarts). */
    data class Release(val tag: String, val apps: List<App>)

    private fun versionOf(context: Context, pkg: String): Version? =
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()?.let(::parse)

    /** The latest release if it's newer than this launcher or the installed Nuvio; blocking. */
    fun check(context: Context): Release? {
        val launcher = versionOf(context, context.packageName)
        val nuvio = versionOf(context, Nuvio.PACKAGE)
        if (launcher == null && nuvio == null) return null
        return try {
            val o = JSONObject(Http.get(LATEST))
            val tag = o.optString("tag_name")
            val latest = parse(tag) ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            val urls = (0 until assets.length()).map { assets.getJSONObject(it) }
                .associate { it.optString("name") to it.optString("browser_download_url") }
            val apps = buildList {
                if (nuvio != null && compare(latest, nuvio) > 0) {
                    val abi = Build.SUPPORTED_ABIS.firstOrNull()
                    (urls["vibe-nuvio-$abi.apk"] ?: urls["vibe-nuvio-universal.apk"])?.let { add(App(Nuvio.PACKAGE, it)) }
                }
                if (launcher != null && compare(latest, launcher) > 0) {
                    urls[LAUNCHER_ASSET]?.let { add(App(context.packageName, it)) }
                }
            }
            if (apps.isEmpty()) null else Release(tag, apps)
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
            null
        }
    }

    /** "v1.2.3" or "v1.2.3-tag4"; null for dev builds. */
    data class Version(val numbers: List<Int>, val suffix: String?)

    fun parse(version: String): Version? {
        if (!version.startsWith("v")) return null
        val core = version.removePrefix("v").substringBefore('-')
        val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
        return Version(numbers, version.substringAfter('-', "").ifEmpty { null })
    }

    /** Numbers first; then a plain release beats a suffixed one, and suffixes compare by their trailing number. */
    fun compare(a: Version, b: Version): Int {
        for (i in 0 until maxOf(a.numbers.size, b.numbers.size)) {
            val d = a.numbers.getOrElse(i) { 0 } - b.numbers.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        if (a.suffix == b.suffix) return 0
        if (a.suffix == null) return 1
        if (b.suffix == null) return -1
        val na = Regex("\\d+$").find(a.suffix)?.value?.toIntOrNull()
        val nb = Regex("\\d+$").find(b.suffix)?.value?.toIntOrNull()
        val la = a.suffix.removeSuffix(na?.toString() ?: "")
        val lb = b.suffix.removeSuffix(nb?.toString() ?: "")
        return if (la == lb && na != null && nb != null) na - nb else a.suffix.compareTo(b.suffix)
    }

    /** Android's "install unknown apps" switch for the launcher; null if it's already on. */
    fun permissionIntent(context: Context): Intent? =
        if (context.packageManager.canRequestPackageInstalls()) null
        else Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Installs every app in [release], one after another; each shows Android's confirmation.
     * Blocking; returns an error message, or null once the first confirmation is up.
     */
    fun install(context: Context, release: Release): String? {
        saveQueue(context, release.apps.drop(1))
        return installOne(context, release.apps.first())
    }

    private fun installOne(context: Context, app: App): String? = try {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(app.pkg) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            val conn = URL(app.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", Http.UA)
            conn.inputStream.use { input ->
                session.openWrite("${app.pkg}.apk", 0, -1).use { out ->
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
        Log.w(TAG, "update install failed for ${app.pkg}", e)
        "Couldn't download the update"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    private fun saveQueue(context: Context, apps: List<App>) {
        prefs(context).edit().putString(QUEUE, apps.joinToString(";") { "${it.pkg} ${it.url}" }).apply()
    }

    private fun popQueue(context: Context): App? {
        val apps = prefs(context).getString(QUEUE, null).orEmpty().split(';').filter { it.isNotBlank() }
            .map { App(it.substringBefore(' '), it.substringAfter(' ')) }
        saveQueue(context, apps.drop(1))
        return apps.firstOrNull()
    }

    /** Receives the installer's status: shows its confirmation, then moves on to the next app. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
                PackageInstaller.STATUS_SUCCESS -> Unit
                else -> Log.w(TAG, "install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
            // Done with one app (or it was cancelled): carry on with the next, if any.
            val next = popQueue(context) ?: return
            val pending = goAsync()
            Thread {
                try { installOne(context.applicationContext, next) } finally { pending.finish() }
            }.start()
        }
    }

    /**
     * Brings the launcher back after it was updated. While an install runs, Android can't start the
     * new launcher, and whatever task sits underneath (often a TV input) comes to the front instead.
     */
    class ReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "couldn't come back after update: ${it.message}") }
        }
    }
}
