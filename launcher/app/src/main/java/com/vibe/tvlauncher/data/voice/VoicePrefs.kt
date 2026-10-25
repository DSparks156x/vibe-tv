package com.vibe.tvlauncher.data.voice

import android.content.Context

/** An OpenAI-compatible chat endpoint. Most providers (and Ollama / LM Studio) speak this API. */
data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    /** Preferred models, best first; matched against the provider's /models list. */
    val preferredModels: List<String>,
    val needsKey: Boolean = true,
    val keyHint: String = "",
)

/**
 * On-device settings for the assistant's language model. The user brings their own key;
 * it is stored only in this app's private prefs and sent only to the provider they picked.
 */
object VoicePrefs {
    private const val PREFS = "voice"

    val PROVIDERS = listOf(
        Provider("off", "Off (offline rules only)", "", emptyList(), needsKey = false),
        Provider(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            // Fastest good fits measured on a TV (Oct 2026): ~0.5 s per request.
            listOf("qwen", "gpt-oss-20b", "gpt-oss", "llama"),
            keyHint = "console.groq.com/keys",
        ),
        Provider(
            "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            listOf("flash-lite", "flash"),
            keyHint = "aistudio.google.com/apikey",
        ),
        Provider(
            "custom", "Custom (OpenAI-compatible URL)", "", emptyList(), needsKey = false,
            keyHint = "e.g. http://192.168.1.10:11434/v1 for Ollama",
        ),
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun provider(c: Context): Provider =
        PROVIDERS.firstOrNull { it.id == prefs(c).getString("provider", "off") } ?: PROVIDERS.first()

    fun setProvider(c: Context, id: String) = prefs(c).edit().putString("provider", id).apply()

    /** Keys are kept per provider so switching back and forth doesn't lose them. */
    fun apiKey(c: Context, p: Provider = provider(c)): String? =
        prefs(c).getString("key_${p.id}", null)?.trim()?.ifEmpty { null }

    fun setApiKey(c: Context, p: Provider, key: String?) {
        prefs(c).edit().apply { if (key.isNullOrBlank()) remove("key_${p.id}") else putString("key_${p.id}", key.trim()) }.apply()
    }

    fun model(c: Context, p: Provider = provider(c)): String? = prefs(c).getString("model_${p.id}", null)

    fun setModel(c: Context, p: Provider, model: String?) {
        prefs(c).edit().apply { if (model == null) remove("model_${p.id}") else putString("model_${p.id}", model) }.apply()
    }

    fun customUrl(c: Context): String? = prefs(c).getString("custom_url", null)?.trim()?.ifEmpty { null }

    fun setCustomUrl(c: Context, url: String?) {
        prefs(c).edit().apply { if (url.isNullOrBlank()) remove("custom_url") else putString("custom_url", url.trim().trimEnd('/')) }.apply()
    }

    fun baseUrl(c: Context, p: Provider = provider(c)): String? = if (p.id == "custom") customUrl(c) else p.baseUrl.ifEmpty { null }

    /** Whether a language model is set up well enough to try. */
    fun configured(c: Context): Boolean {
        val p = provider(c)
        if (p.id == "off") return false
        return baseUrl(c, p) != null && (!p.needsKey || apiKey(c, p) != null)
    }

    /** "gsk_…a1b2" for display. */
    fun masked(key: String?): String = when {
        key == null -> "Not set"
        key.length <= 12 -> "Set"
        else -> key.take(4) + "…" + key.takeLast(4)
    }
}
