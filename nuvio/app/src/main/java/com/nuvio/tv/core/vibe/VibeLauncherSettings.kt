package com.nuvio.tv.core.vibe

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.runtime.AppRestarter
import com.nuvio.tv.data.local.ServerConfigurationStore
import com.nuvio.tv.data.remote.ServerDiscoveryException
import com.nuvio.tv.data.remote.ServerDiscoveryFailure
import com.nuvio.tv.data.remote.ServerDiscoveryPolicy
import com.nuvio.tv.data.remote.ServerDiscoveryService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vibe fork: "get_settings" / "set_settings" for [VibeLauncherProvider]. The launcher's settings
 * screen and setup page configure Nuvio's service keys ([RuntimeConfig]) and its sync backend.
 * Secrets are never returned, only "set" or "".
 */
class VibeLauncherSettings(
    private val context: Context,
    private val serverConfigurationStore: ServerConfigurationStore,
    private val serverDiscoveryService: ServerDiscoveryService,
    private val authManager: AuthManager,
    private val appRestarter: AppRestarter
) {
    fun getSettingsJson(): String {
        val fields = JSONArray()
        val active = serverConfigurationStore.loadActive()
        fields.put(
            JSONObject()
                .put("key", KEY_BACKEND_URL)
                .put("label", "Sync server URL")
                .put(
                    "help",
                    "Your Nuvio self-host backend (e.g. https://nuvio.example.com). Empty = the build default. " +
                        "Changing it signs Nuvio out; sign in again on the TV."
                )
                .put("type", RuntimeConfig.Type.URL.wire)
                .put("group", "Account")
                .put("value", if (active.isCustom) active.backendUrl else "")
        )
        RuntimeConfig.fields.forEach { f ->
            val value = RuntimeConfig.value(f.key)
            fields.put(
                JSONObject()
                    .put("key", f.key)
                    .put("label", f.label)
                    .put("help", f.help)
                    .put("type", f.type.wire)
                    .put("group", f.group)
                    .put("value", if (f.type == RuntimeConfig.Type.SECRET) (if (value.isBlank()) "" else "set") else value)
            )
        }
        return JSONObject().put("fields", fields).toString()
    }

    /** Applies [values] (key -> string) and returns {"results":{key:"ok"|error}}. */
    suspend fun setSettings(values: Map<String, String?>): String {
        val results = JSONObject()
        var restart = false
        values.forEach { (key, raw) ->
            val value = raw?.trim().orEmpty()
            val outcome = when {
                key == KEY_BACKEND_URL -> setBackend(value).also { if (it.second) restart = true }.first
                RuntimeConfig.field(key) == null -> "unknown setting"
                else -> {
                    val field = RuntimeConfig.field(key)!!
                    val normalized = normalize(field, value)
                    when {
                        normalized == null -> "not a valid http(s) URL"
                        normalized == RuntimeConfig.override(key).orEmpty() -> "ok"
                        RuntimeConfig.setOverride(key, normalized) -> { restart = true; "ok" }
                        else -> "could not save"
                    }
                }
            }
            results.put(key, outcome)
        }
        context.contentResolver.notifyChange(Uri.parse("content://${context.packageName}.launcher/settings"), null)
        if (restart) scheduleRestart()
        return JSONObject().put("results", results).toString()
    }

    /** Returns (result, needsRestart). */
    private suspend fun setBackend(value: String): Pair<String, Boolean> {
        val active = serverConfigurationStore.loadActive()
        if (value.isBlank() || ServerDiscoveryPolicy.matchesBackend(
                runCatching { ServerDiscoveryPolicy.discoveryUrl(value) }.getOrDefault(""), BuildConfig.SUPABASE_URL
            )
        ) {
            if (!active.isCustom) return "ok" to false
            if (authManager.prepareForServerSwitch().isFailure) return "could not sign out of the current server" to false
            return (if (serverConfigurationStore.useOfficial()) "ok" else "could not save") to true
        }
        val discovered = serverDiscoveryService.discover(value).getOrElse { error ->
            val failure = (error as? ServerDiscoveryException)?.failure
            return when (failure) {
                ServerDiscoveryFailure.INVALID_URL -> "not a valid server URL"
                ServerDiscoveryFailure.NOT_SELF_HOSTED -> "that server is not a Nuvio self-host backend"
                null -> "could not reach the server"
                else -> "server check failed (${failure.name.lowercase()})"
            } to false
        }
        if (active.isCustom && active.backendUrl.trimEnd('/') == discovered.backendUrl.trimEnd('/')) return "ok" to false
        if (authManager.prepareForServerSwitch().isFailure) return "could not sign out of the current server" to false
        if (!serverConfigurationStore.saveCustom(discovered)) return "custom servers are disabled in this build" to false
        return "ok" to true
    }

    private fun normalize(field: RuntimeConfig.Field, value: String): String? {
        if (value.isEmpty() || field.type != RuntimeConfig.Type.URL) return value
        val lower = value.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        return if (value.endsWith("/")) value else "$value/" // Retrofit base URLs need the slash
    }

    /**
     * Most values are read when Nuvio's network clients are built, so restart the process. If Nuvio
     * is in the background just end it (it starts again on next use) so nothing pops over the
     * launcher; if it is on screen, use Nuvio's own restart.
     */
    private fun scheduleRestart() {
        Handler(Looper.getMainLooper()).postDelayed({
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            Log.i(TAG, "Restarting after settings change (importance=${info.importance})")
            if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                appRestarter.restart()
            } else {
                Process.killProcess(Process.myPid())
            }
        }, RESTART_DELAY_MS)
    }

    companion object {
        const val METHOD_GET = "get_settings"
        const val METHOD_SET = "set_settings"
        const val KEY_BACKEND_URL = "backend_url"
        private const val TAG = "VibeLauncherSettings"
        private const val RESTART_DELAY_MS = 1_000L
    }
}
