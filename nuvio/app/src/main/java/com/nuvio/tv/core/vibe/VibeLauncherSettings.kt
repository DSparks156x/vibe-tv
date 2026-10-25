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
import com.nuvio.tv.data.local.AccountMode
import com.nuvio.tv.data.local.ServerConfigurationStore
import com.nuvio.tv.data.remote.ServerDiscoveryException
import com.nuvio.tv.data.remote.ServerDiscoveryFailure
import com.nuvio.tv.data.remote.ServerDiscoveryPolicy
import com.nuvio.tv.data.remote.ServerDiscoveryService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vibe fork: "get_settings" / "set_settings" for [VibeLauncherProvider]. Only what a person
 * provides themselves is settable here: which account backend Nuvio uses (none, the official
 * service, or their own server) and that server's URL. Service API keys are baked into release
 * builds by CI from repository secrets.
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
        val mode = serverConfigurationStore.accountMode()
        fields.put(
            JSONObject()
                .put("key", KEY_ACCOUNT_MODE)
                .put("label", "Nuvio account")
                .put(
                    "help",
                    "Accounts sync library, watch progress and add-ons. Switching signs Nuvio out; sign in again on the TV."
                )
                .put("type", "choice")
                .put("group", "Account")
                .put("value", mode.wire)
                .put(
                    "choices",
                    JSONArray()
                        .put(JSONObject().put("id", AccountMode.NONE.wire).put("label", "No account"))
                        .put(JSONObject().put("id", AccountMode.OFFICIAL.wire).put("label", "Official Nuvio account"))
                        .put(JSONObject().put("id", AccountMode.CUSTOM.wire).put("label", "My own server"))
                )
        )
        fields.put(
            JSONObject()
                .put("key", KEY_BACKEND_URL)
                .put("label", "Sync server URL")
                .put(
                    "help",
                    "Your Nuvio self-host backend (e.g. https://nuvio.example.com). Empty = the build default. " +
                        "Changing it signs Nuvio out; sign in again on the TV."
                )
                .put("type", "url")
                .put("group", "Account")
                .put("value", if (mode == AccountMode.CUSTOM) active.backendUrl else "")
                .put(
                    "show_when",
                    JSONObject().put("key", KEY_ACCOUNT_MODE).put("values", JSONArray().put(AccountMode.CUSTOM.wire))
                )
        )
        return JSONObject().put("fields", fields).toString()
    }

    /** Applies [values] (key -> string) and returns {"results":{key:"ok"|error}}. */
    suspend fun setSettings(values: Map<String, String?>): String {
        val results = JSONObject()
        var restart = false
        // account_mode first, so {account_mode: custom, backend_url: ...} in one call works.
        val ordered = values.entries.sortedBy { if (it.key == KEY_ACCOUNT_MODE) 0 else 1 }
        ordered.forEach { (key, raw) ->
            val value = raw?.trim().orEmpty()
            val outcome = when (key) {
                KEY_ACCOUNT_MODE -> {
                    val pendingUrl = values[KEY_BACKEND_URL]?.trim().orEmpty()
                    setAccountMode(value, pendingUrl.isNotEmpty()).also { if (it.second) restart = true }.first
                }
                KEY_BACKEND_URL -> setBackend(value).also { if (it.second) restart = true }.first
                else -> "unknown setting"
            }
            results.put(key, outcome)
        }
        context.contentResolver.notifyChange(Uri.parse("content://${context.packageName}.launcher/settings"), null)
        if (restart) scheduleRestart()
        return JSONObject().put("results", results).toString()
    }

    /** Returns (result, needsRestart). [urlFollows]: a backend_url in the same call does the custom switch. */
    private suspend fun setAccountMode(value: String, urlFollows: Boolean): Pair<String, Boolean> {
        val target = AccountMode.fromWire(value) ?: return "unknown account mode" to false
        if (target == serverConfigurationStore.accountMode()) return "ok" to false
        return when (target) {
            AccountMode.NONE -> {
                if (authManager.prepareForServerSwitch().isFailure) return "could not sign out of the current server" to false
                (if (serverConfigurationStore.saveNoAccount()) "ok" else "could not save") to true
            }
            AccountMode.OFFICIAL -> {
                val official = serverDiscoveryService.discover(ServerConfigurationStore.OFFICIAL_BACKEND_URL)
                    .getOrElse { return "could not reach the official Nuvio server" to false }
                if (authManager.prepareForServerSwitch().isFailure) return "could not sign out of the current server" to false
                (if (serverConfigurationStore.saveOfficial(official)) "ok" else "could not save") to true
            }
            AccountMode.CUSTOM -> when {
                urlFollows -> "ok" to false
                serverConfigurationStore.hasBuildBackend() -> {
                    // This build has its own server baked in: switch back to it.
                    if (authManager.prepareForServerSwitch().isFailure) return "could not sign out of the current server" to false
                    (if (serverConfigurationStore.useOfficial()) "ok" else "could not save") to true
                }
                else -> "enter your server URL" to false
            }
        }
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

    /**
     * The backend is read when Nuvio's network clients are built, so restart the process. If Nuvio
     * is in the background just end it (it starts again on next use) so nothing pops over the
     * launcher; if it is on screen (usually because the launcher just fired `nuvio://account`
     * after the change), restart straight into that link so the user lands on sign-in.
     */
    private fun scheduleRestart() {
        Handler(Looper.getMainLooper()).postDelayed({
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            Log.i(TAG, "Restarting after settings change (importance=${info.importance})")
            if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                appRestarter.restart(Uri.parse(ACCOUNT_DEEP_LINK))
            } else {
                Process.killProcess(Process.myPid())
            }
        }, RESTART_DELAY_MS)
    }

    companion object {
        const val METHOD_GET = "get_settings"
        const val METHOD_SET = "set_settings"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_ACCOUNT_MODE = "account_mode"
        private const val TAG = "VibeLauncherSettings"
        // Just long enough for the call() reply to reach the launcher.
        private const val RESTART_DELAY_MS = 200L
        private const val ACCOUNT_DEEP_LINK = "nuvio://account"
    }
}
