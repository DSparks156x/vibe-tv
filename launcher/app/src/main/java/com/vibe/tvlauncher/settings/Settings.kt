package com.vibe.tvlauncher.settings

import android.content.Context
import com.vibe.tvlauncher.data.Nuvio
import com.vibe.tvlauncher.data.voice.VoicePrefs

enum class FieldType { Text, Secret, Choice, Toggle }

/**
 * One user setting. Both the TV settings screen and the phone/computer setup page are built from this list,
 * so everything a release build can't ship (keys, servers) is settable without a rebuild.
 */
data class Field(
    val key: String,
    val section: String,
    val label: String,
    val help: String = "",
    val type: FieldType = FieldType.Text,
    /** id to label, for [FieldType.Choice]. */
    val choices: List<Pair<String, String>> = emptyList(),
    /** Current value. Secrets report "set" or "" and are never read back. */
    val value: String = "",
    /** Only shown while the setting [showWhen].first has one of the values in [showWhen].second. */
    val showWhen: Pair<String, Set<String>>? = null,
    /** What an empty value means, e.g. "Default" when the build has one. */
    val emptyLabel: String = "Not set",
)

/** Drops fields whose [Field.showWhen] condition doesn't hold for the current values. */
fun List<Field>.visible(): List<Field> {
    val values = associate { it.key to it.value }
    return filter { f -> f.showWhen?.let { (k, allowed) -> values[k] in allowed } ?: true }
}

/** Launcher-wide preferences that aren't the assistant's (those live in [VoicePrefs]). */
object LauncherPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Whether home rows lean on your watch history. Off = plain trending, nothing hidden. */
    fun personalize(c: Context) = prefs(c).getBoolean("personalize", true)

    /** How hard Top picks lean toward your genres vs. what's popular: 0..1. */
    fun tasteWeight(c: Context) = when (prefs(c).getString("taste", "balanced")) {
        "light" -> 0.4
        "strong" -> 0.9
        else -> 0.7
    }

    /** "auto", "f" or "c". */
    fun units(c: Context) = prefs(c).getString("units", "auto") ?: "auto"

    fun location(c: Context) = prefs(c).getString("location", null)

    /** Home rows that can be switched off: key to label. Continue watching always shows. */
    val HOME_ROWS = listOf(
        "newep" to "New episodes",
        "picks" to "Top picks for you",
        "apps" to "Your apps",
        "taste" to "Because you watch…",
        "movies" to "Trending movies",
        "shows" to "Trending shows",
        "addon" to "Your add-on catalogs",
        "trailers" to "Trailers on YouTube",
        "library" to "Your library",
        "rated" to "Top rated movies",
    )

    fun hiddenRows(c: Context): Set<String> = HOME_ROWS.map { it.first }.filter { !prefs(c).getBoolean("row_$it", true) }.toSet()

    internal fun put(c: Context, key: String, value: String?) =
        prefs(c).edit().apply { if (value.isNullOrBlank()) remove(key) else putString(key, value) }.apply()

    internal fun putBool(c: Context, key: String, value: Boolean) = prefs(c).edit().putBoolean(key, value).apply()

    internal fun get(c: Context, key: String) = prefs(c).getString(key, null)
}

object SettingsRegistry {
    const val NUVIO_PREFIX = "nuvio."

    /** All settings, launcher's own first, then whatever the installed Nuvio build exposes. Blocking (queries Nuvio). */
    fun fields(c: Context): List<Field> = launcherFields(c) + nuvioFields(c)

    private fun launcherFields(c: Context): List<Field> = buildList {
        val provider = VoicePrefs.provider(c)
        add(
            Field(
                "assistant.provider", "Assistant", "Language model",
                "Plain commands (\"play Dune\") always work offline. A model handles looser requests; bring your own key.",
                FieldType.Choice, VoicePrefs.PROVIDERS.map { it.id to it.label }, provider.id,
            ),
        )
        // Only the picked provider's fields show; keys are still kept per provider.
        VoicePrefs.PROVIDERS.filter { it.id != "off" }.forEach { p ->
            val only = "assistant.provider" to setOf(p.id)
            if (p.id == "custom") {
                add(
                    Field(
                        "assistant.custom_url", "Assistant", "Server URL",
                        "Any OpenAI-compatible endpoint, ${p.keyHint}", FieldType.Text,
                        value = VoicePrefs.customUrl(c).orEmpty(), showWhen = only,
                    ),
                )
            }
            add(
                Field(
                    "assistant.key.${p.id}", "Assistant", if (p.needsKey) "API key" else "API key (optional)",
                    if (p.needsKey) "Get one at ${p.keyHint}" else "Only if your server needs one",
                    FieldType.Secret, value = if (VoicePrefs.apiKey(c, p) != null) "set" else "", showWhen = only,
                ),
            )
        }
        add(
            Field(
                "assistant.model", "Assistant", "Model",
                "Empty = pick the provider's best fast model automatically.",
                FieldType.Text, value = VoicePrefs.model(c, provider).orEmpty(),
                showWhen = "assistant.provider" to VoicePrefs.PROVIDERS.filter { it.id != "off" }.map { it.id }.toSet(),
            ),
        )

        add(
            Field(
                "home.personalize", "Home", "Personalized rows",
                "Rank Top picks and build \"Because you watch…\" rows from your Nuvio history.",
                FieldType.Toggle, value = LauncherPrefs.personalize(c).toString(),
            ),
        )
        add(
            Field(
                "home.taste", "Home", "Lean on my taste",
                "How much Top picks favor your genres over what's popular.",
                FieldType.Choice, listOf("light" to "A little", "balanced" to "Balanced", "strong" to "A lot"),
                LauncherPrefs.get(c, "taste") ?: "balanced",
            ),
        )

        LauncherPrefs.HOME_ROWS.forEach { (key, label) ->
            add(
                Field(
                    "row.$key", "Home rows", label, type = FieldType.Toggle,
                    value = (key !in LauncherPrefs.hiddenRows(c)).toString(),
                ),
            )
        }

        add(
            Field(
                "weather.location", "Clock & weather", "Weather location",
                "A city (\"Denver\") or \"lat,lon\". Empty = guess from your internet connection.",
                FieldType.Text, value = LauncherPrefs.location(c).orEmpty(),
            ),
        )
        add(
            Field(
                "weather.units", "Clock & weather", "Temperature",
                choices = listOf("auto" to "Automatic", "f" to "°F", "c" to "°C"), type = FieldType.Choice,
                value = LauncherPrefs.units(c),
            ),
        )
        add(
            Field(
                "profile.avatar", "Profile", "Avatar image URL",
                "Overrides the Nuvio profile picture. Empty = use Nuvio's.",
                FieldType.Text, value = LauncherPrefs.get(c, "avatarUrl").orEmpty(),
            ),
        )
    }

    private fun nuvioFields(c: Context): List<Field> = Nuvio.settings(c).map { s ->
        Field(
            key = NUVIO_PREFIX + s.key,
            section = if (s.group.isEmpty()) "Nuvio" else "Nuvio · ${s.group}",
            label = s.label,
            help = s.help,
            type = when (s.type) {
                "secret" -> FieldType.Secret
                "bool" -> FieldType.Toggle
                else -> FieldType.Text
            },
            value = s.value,
            emptyLabel = "Default",
        )
    }

    /**
     * Applies changes. Returns key -> null on success or an error message. Blank text clears a value;
     * blank secrets are ignored (use [CLEAR] to remove a saved key) so a form can leave them untouched.
     */
    fun apply(c: Context, changes: Map<String, String>): Map<String, String?> {
        val results = LinkedHashMap<String, String?>()
        val nuvio = LinkedHashMap<String, String>()
        val fields = launcherFields(c).associateBy { it.key }
        for ((key, raw) in changes) {
            val v = raw.trim()
            if (key.startsWith(NUVIO_PREFIX)) {
                nuvio[key.removePrefix(NUVIO_PREFIX)] = if (v == CLEAR) "" else v
                continue
            }
            val f = fields[key]
            if (f == null) {
                results[key] = "Unknown setting"
                continue
            }
            if (f.type == FieldType.Secret && v.isEmpty()) continue
            results[key] = applyLauncher(c, f, if (v == CLEAR) "" else v)
        }
        if (nuvio.isNotEmpty()) {
            Nuvio.setSettings(c, nuvio).forEach { (k, r) -> results[NUVIO_PREFIX + k] = if (r == "ok") null else r }
        }
        return results
    }

    /** Typed into a secret field to delete the saved value. */
    const val CLEAR = "-"

    private fun applyLauncher(c: Context, f: Field, v: String): String? {
        when (f.key) {
            "assistant.provider" -> {
                if (VoicePrefs.PROVIDERS.none { it.id == v }) return "Unknown provider"
                VoicePrefs.setProvider(c, v)
            }
            "assistant.custom_url" -> {
                if (v.isNotEmpty() && !v.startsWith("http://") && !v.startsWith("https://")) return "Must start with http:// or https://"
                VoicePrefs.setCustomUrl(c, v)
            }
            "assistant.model" -> VoicePrefs.setModel(c, VoicePrefs.provider(c), v.ifEmpty { null })
            in LauncherPrefs.HOME_ROWS.map { "row.${it.first}" } ->
                LauncherPrefs.putBool(c, "row_" + f.key.removePrefix("row."), v.toBooleanStrictOrNull() ?: return "Use true or false")
            "home.personalize" -> LauncherPrefs.putBool(c, "personalize", v.toBooleanStrictOrNull() ?: return "Use true or false")
            "home.taste" -> {
                if (f.choices.none { it.first == v }) return "Unknown choice"
                LauncherPrefs.put(c, "taste", v)
            }
            "weather.location" -> LauncherPrefs.put(c, "location", v)
            "weather.units" -> {
                if (f.choices.none { it.first == v }) return "Unknown choice"
                LauncherPrefs.put(c, "units", v)
            }
            "profile.avatar" -> {
                if (v.isNotEmpty() && !v.startsWith("http")) return "Must be an http(s) URL"
                LauncherPrefs.put(c, "avatarUrl", v)
            }
            else -> {
                if (!f.key.startsWith("assistant.key.")) return "Unknown setting"
                val p = VoicePrefs.PROVIDERS.firstOrNull { it.id == f.key.removePrefix("assistant.key.") } ?: return "Unknown provider"
                VoicePrefs.setApiKey(c, p, v.ifEmpty { null })
            }
        }
        return null
    }
}
