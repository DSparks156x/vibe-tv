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
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import kotlinx.coroutines.flow.first
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
 * builds by CI from repository secrets. The "Playback" group exposes the auto-pick settings of the
 * active profile; they are written through [PlayerSettingsDataStore] so they sync like in-app changes.
 *
 * Field types: "choice" (choices: [{id,label}]), "url", "text", "toggle" (value "true"/"false").
 * A field with show_when {key, values} only applies while that field's value is one of values.
 */
class VibeLauncherSettings(
    private val context: Context,
    private val serverConfigurationStore: ServerConfigurationStore,
    private val serverDiscoveryService: ServerDiscoveryService,
    private val authManager: AuthManager,
    private val appRestarter: AppRestarter,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) {
    suspend fun getSettingsJson(): String {
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
        playbackFields(playerSettingsDataStore.playerSettings.first()).forEach(fields::put)
        return JSONObject().put("fields", fields).toString()
    }

    private fun playbackFields(settings: PlayerSettings): List<JSONObject> {
        val autoModes = JSONArray().put(MODE_FIRST).put(MODE_REGEX)
        val timeout = settings.streamAutoPlayTimeoutSeconds
        val timeoutChoices = (TIMEOUT_CHOICES + timeout).distinct().sorted()
        return listOf(
            JSONObject()
                .put("key", KEY_AUTO_PLAY_MODE)
                .put("label", "Pick a stream automatically")
                .put("help", "Manual shows the stream list. First stream plays the top result; Regex match plays the first one whose name matches.")
                .put("type", "choice")
                .put("group", GROUP_PLAYBACK)
                .put("value", settings.streamAutoPlayMode.wire())
                .put(
                    "choices",
                    JSONArray()
                        .put(choice(MODE_MANUAL, "Manual"))
                        .put(choice(MODE_FIRST, "First stream"))
                        .put(choice(MODE_REGEX, "Regex match"))
                ),
            JSONObject()
                .put("key", KEY_AUTO_PLAY_REGEX)
                .put("label", "Stream name pattern")
                .put("help", "Regular expression matched against stream names, e.g. 2160p|4K.")
                .put("type", "text")
                .put("group", GROUP_PLAYBACK)
                .put("value", settings.streamAutoPlayRegex)
                .put("show_when", showWhen(KEY_AUTO_PLAY_MODE, JSONArray().put(MODE_REGEX))),
            JSONObject()
                .put("key", KEY_AUTO_PLAY_TIMEOUT)
                .put("label", "Wait for sources")
                .put("help", "How long to wait for slower sources before picking.")
                .put("type", "choice")
                .put("group", GROUP_PLAYBACK)
                .put("value", timeoutWire(timeout))
                .put("choices", JSONArray().apply { timeoutChoices.forEach { put(choice(timeoutWire(it), timeoutLabel(it))) } })
                .put("show_when", showWhen(KEY_AUTO_PLAY_MODE, autoModes)),
            JSONObject()
                .put("key", KEY_NEXT_EPISODE)
                .put("label", "Auto-play next episode")
                .put("help", "When an episode ends, pick a stream for the next one and play it.")
                .put("type", "toggle")
                .put("group", GROUP_PLAYBACK)
                .put("value", settings.streamAutoPlayNextEpisodeEnabled.toString()),
            JSONObject()
                .put("key", KEY_REUSE_LAST_LINK)
                .put("label", "Reuse last link")
                .put("help", "Resume with the stream you last played for a title, without searching again.")
                .put("type", "toggle")
                .put("group", GROUP_PLAYBACK)
                .put("value", settings.streamReuseLastLinkEnabled.toString())
        )
    }

    /** Returns the result for a Playback key, or null when [key] is not one. */
    private suspend fun setPlayback(key: String, value: String): String? = when (key) {
        KEY_AUTO_PLAY_MODE -> when (value) {
            MODE_MANUAL -> StreamAutoPlayMode.MANUAL
            MODE_FIRST -> StreamAutoPlayMode.FIRST_STREAM
            MODE_REGEX -> StreamAutoPlayMode.REGEX_MATCH
            else -> null
        }?.let { playerSettingsDataStore.setStreamAutoPlayMode(it); "ok" } ?: "unknown mode"
        KEY_AUTO_PLAY_REGEX ->
            if (runCatching { Regex(value) }.isSuccess) {
                playerSettingsDataStore.setStreamAutoPlayRegex(value); "ok"
            } else {
                "not a valid pattern"
            }
        KEY_AUTO_PLAY_TIMEOUT -> parseTimeout(value)
            ?.let { playerSettingsDataStore.setStreamAutoPlayTimeoutSeconds(it); "ok" } ?: "unknown timeout"
        KEY_NEXT_EPISODE -> parseToggle(value)
            ?.let { playerSettingsDataStore.setStreamAutoPlayNextEpisodeEnabled(it); "ok" } ?: "expected true or false"
        KEY_REUSE_LAST_LINK -> parseToggle(value)
            ?.let { playerSettingsDataStore.setStreamReuseLastLinkEnabled(it); "ok" } ?: "expected true or false"
        else -> null
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
                else -> setPlayback(key, value) ?: "unknown setting"
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
        const val KEY_AUTO_PLAY_MODE = "stream_auto_play_mode"
        const val KEY_AUTO_PLAY_REGEX = "stream_auto_play_regex"
        const val KEY_AUTO_PLAY_TIMEOUT = "stream_auto_play_timeout_seconds"
        const val KEY_NEXT_EPISODE = "stream_auto_play_next_episode_enabled"
        const val KEY_REUSE_LAST_LINK = "stream_reuse_last_link_enabled"
        private const val GROUP_PLAYBACK = "Playback"
        private const val MODE_MANUAL = "manual"
        private const val MODE_FIRST = "first_stream"
        private const val MODE_REGEX = "regex"
        private const val TIMEOUT_UNLIMITED = "unlimited"
        private val TIMEOUT_CHOICES = listOf(0, 3, 5, 10, 15, 30, PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED)

        internal fun StreamAutoPlayMode.wire(): String = when (this) {
            StreamAutoPlayMode.MANUAL -> MODE_MANUAL
            StreamAutoPlayMode.FIRST_STREAM -> MODE_FIRST
            StreamAutoPlayMode.REGEX_MATCH -> MODE_REGEX
        }

        internal fun timeoutWire(seconds: Int): String =
            if (seconds == PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED) TIMEOUT_UNLIMITED else seconds.toString()

        internal fun timeoutLabel(seconds: Int): String = when (seconds) {
            0 -> "No wait"
            PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED -> "Until all sources answer"
            else -> "$seconds seconds"
        }

        internal fun parseTimeout(value: String): Int? =
            if (value == TIMEOUT_UNLIMITED) {
                PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED
            } else {
                value.toIntOrNull()?.takeIf { it in PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_VALUES }
            }

        internal fun parseToggle(value: String): Boolean? = when (value.lowercase()) {
            "true", "1", "on" -> true
            "false", "0", "off" -> false
            else -> null
        }

        private fun choice(id: String, label: String) = JSONObject().put("id", id).put("label", label)

        private fun showWhen(key: String, values: JSONArray) = JSONObject().put("key", key).put("values", values)
        private const val TAG = "VibeLauncherSettings"
        // Just long enough for the call() reply to reach the launcher.
        private const val RESTART_DELAY_MS = 200L
        private const val ACCOUNT_DEEP_LINK = "nuvio://account"
    }
}
