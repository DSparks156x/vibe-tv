package com.vibe.tvlauncher.data.voice

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the model gets to know about this TV, so "continue my show" means something. */
data class AssistantContext(
    val now: String,
    val continueWatching: List<String>,
    val library: List<String>,
    val apps: List<String>,
) {
    fun render(): String = buildString {
        appendLine("Now: $now")
        appendLine()
        appendLine("Continue watching (most recent first; refer to these by id):")
        if (continueWatching.isEmpty()) appendLine("(empty)") else continueWatching.forEach { appendLine(it) }
        appendLine()
        appendLine("Library / watchlist (refer to these by id):")
        if (library.isEmpty()) appendLine("(empty)") else library.forEach { appendLine(it) }
        appendLine()
        appendLine("Installed apps: " + apps.joinToString(", "))
    }
}

class LlmException(message: String, val authFailed: Boolean = false) : IOException(message)

/**
 * Natural-language understanding through any OpenAI-compatible chat endpoint
 * (Groq, Gemini, OpenRouter, Ollama, LM Studio, ...), with the user's own key.
 */
object LlmInterpreter {
    private const val TAG = "VibeVoice"

    private val SYSTEM = """
        You are the voice assistant of a TV home screen. The user spoke (or typed) a request with the remote.
        Speech recognition can mishear words, so read the request generously (e.g. "brake in bad" = Breaking Bad).
        Turn the request into ONE action for the TV. The TV plays movies and shows through the user's own
        streaming app (it can play nearly any movie or series by title), searches YouTube, and opens installed apps.

        Actions:
        - play: watch a specific movie or show now ("play dune", "put on the new dune",
          "that show with the meth teacher"). Put the exact official title(s) in titles, best guess first.
        - resume: continue something from Continue watching. Put its id (like "c2") in refs.
        - next_episode: the next episode of a show they are watching. Put the show's Continue watching id in refs if known.
        - show_results: browse or recommend ("something funny from the 90s", "movies like interstellar",
          "what was I watching yesterday", "Tom Hanks movies"). For recommendations, list 6 to 10 real,
          well-known titles that fit. For questions about their history, put the matching ids in refs.
        - open_app: open an installed app. Put the app's name exactly as listed in app.
        - youtube: anything that lives on YouTube (music, clips, trailers, video essays, how-to, a creator).
          Put the YouTube search text in query.

        Rules:
        - Only name real, released movies and series, with the official English title and release year.
          type is "movie", "series" or "any". For "the new X" / "the latest X" pick the most recent release before today.
        - If the request names something in Continue watching, prefer resume with its ref instead of play.
        - query is a short search phrase for the request. Always fill it.
        - reply is a short, friendly line (max 12 words) shown on screen, like "Playing Dune: Part Two".
        - Use 0 for unknown year/season/episode and "" for no app.
        - If the request is not about watching anything, answer in reply in one short sentence and use
          show_results with an empty query and no titles.

        Answer with ONLY a JSON object, no other text, in exactly this shape:
        {"action":"play|resume|next_episode|show_results|open_app|youtube","reply":"","query":"",
         "titles":[{"name":"","year":0,"type":"movie|series|any"}],"refs":[],"app":"","season":0,"episode":0}
    """.trimIndent()

    /** Blocking; call off the main thread. */
    fun interpret(baseUrl: String, key: String?, model: String, context: AssistantContext, utterance: String): VoiceIntent {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_tokens", 600)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM + "\n\n" + context.render()))
                    .put(JSONObject().put("role", "user").put("content", utterance)),
            )
        // Keep "thinking" models quick; this is a one-line classification, not a puzzle.
        reasoningEffort(model)?.let { body.put("reasoning_effort", it) }
        val started = System.currentTimeMillis()
        val response = try {
            post("$baseUrl/chat/completions", key, body)
        } catch (e: LlmException) {
            when {
                e.message?.contains("HTTP 404") == true -> throw ModelGone(model)
                e.message?.contains("HTTP 400") == true && body.has("reasoning_effort") -> {
                    body.remove("reasoning_effort")
                    post("$baseUrl/chat/completions", key, body)
                }
                else -> throw e
            }
        }
        val text = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        Log.i(TAG, "llm $model took ${System.currentTimeMillis() - started} ms -> $text")
        val json = text.substringAfter('{', "").let { if (it.isEmpty()) "" else "{" + it.substringBeforeLast('}') + "}" }
        if (json.isEmpty()) throw LlmException("The model gave no answer")
        return parse(JSONObject(json))
    }

    private fun reasoningEffort(model: String): String? {
        val m = model.lowercase()
        return when {
            "gpt-oss" in m -> "low"
            "qwen3" in m -> "none"
            else -> null
        }
    }

    /** Model ids the endpoint offers, for the settings picker. */
    fun models(baseUrl: String, key: String?): List<String> {
        val c = open("$baseUrl/models", key)
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw LlmException("Key rejected (HTTP $code)", authFailed = true)
            if (code !in 200..299) throw LlmException("HTTP $code")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val arr = o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                (m.optString("id").ifEmpty { m.optString("name") }).removePrefix("models/").ifEmpty { null }
            }.sorted()
        } finally {
            c.disconnect()
        }
    }

    /** Picks the provider's preferred chat model from what's available. */
    fun pickDefault(available: List<String>, preferred: List<String>, exclude: Set<String> = emptySet()): String? {
        val chat = available.filter { id ->
            id !in exclude && listOf("embed", "whisper", "tts", "guard", "image", "vision", "audio", "aqa", "live", "thinking", "orpheus", "transcribe", "robotics", "research", "computer")
                .none { it in id.lowercase() }
        }
        // Newest stable version first ("gemini-3.5-flash-lite" over "gemini-2.5-flash-lite"), previews last.
        val ranked = chat.sortedWith(
            compareBy<String> { listOf("preview", "exp", "-latest").any { t -> t in it } }
                .thenByDescending { version(it) }
                .thenBy { it.length },
        )
        for (p in preferred) ranked.firstOrNull { it == p }?.let { return it }
        for (p in preferred) ranked.firstOrNull { p in it }?.let { return it }
        return ranked.firstOrNull()
    }

    private fun version(id: String): Double =
        Regex("(\\d+(?:\\.\\d+)?)").find(id.substringAfter('-', id))?.value?.toDoubleOrNull() ?: 0.0

    /** The configured model is gone (provider retired it); callers pick another one. */
    class ModelGone(val model: String) : Exception("Model $model is no longer available")

    private fun open(url: String, key: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "VibeTV/0.1 (Android TV)")
            key?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun post(url: String, key: String?, body: JSONObject): JSONObject {
        val c = open(url, key)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) throw LlmException("The API key was rejected", authFailed = true)
            if (code == 429) throw LlmException("The model's free-tier limit was hit, try again in a bit")
            if (code !in 200..299) throw LlmException("Model error (HTTP $code)").also { Log.w(TAG, "llm HTTP $code: ${text.take(300)}") }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun parse(o: JSONObject): VoiceIntent {
        val action = when (o.optString("action")) {
            "play" -> Action.PLAY
            "resume" -> Action.RESUME
            "next_episode" -> Action.NEXT_EPISODE
            "open_app" -> Action.OPEN_APP
            "youtube" -> Action.YOUTUBE
            else -> Action.SHOW_RESULTS
        }
        val titles = o.optJSONArray("titles")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull null
                val name = t.optString("name").trim().ifEmpty { return@mapNotNull null }
                TitleGuess(name, t.optInt("year").takeIf { it > 0 }, t.optString("type").takeIf { it == "movie" || it == "series" })
            }
        } ?: emptyList()
        val refs = o.optJSONArray("refs")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        return VoiceIntent(
            action = action,
            query = o.optString("query").trim(),
            titles = titles,
            refs = refs,
            app = o.optString("app").trim().ifEmpty { null },
            season = o.optInt("season").takeIf { it > 0 },
            episode = o.optInt("episode").takeIf { it > 0 },
            reply = o.optString("reply").trim().ifEmpty { null },
            source = VoiceIntent.Source.LLM,
        )
    }
}
