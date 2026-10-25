package com.vibe.tvlauncher.data.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The assistant's brain: a fast offline rule parser for plain commands, the user's own
 * language model (any OpenAI-compatible endpoint, if they set one up) for everything else,
 * and the resolver to ground whatever comes out in real titles and apps.
 */
object Assistant {
    private const val TAG = "VibeVoice"

    suspend fun handle(context: Context, transcript: String): VoiceResults = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val snap = Resolver.snapshot(context)
        val rule = RuleParser.parse(transcript)
        Log.i(TAG, "snapshot ${System.currentTimeMillis() - t0} ms")
        val smart = VoicePrefs.configured(context)
        Log.i(TAG, "\"$transcript\" -> rules ${rule.intent.action} \"${rule.intent.query}\" certain=${rule.certain}")

        // Plain commands are answered locally when they clearly resolve: faster, free, works offline.
        if (!smart) return@withContext Resolver.resolve(transcript, rule.intent, snap, null)
        if (rule.certain) {
            val local = Resolver.resolve(transcript, rule.intent, snap, null)
            if (clearlyResolved(local)) return@withContext local
        }

        val provider = VoicePrefs.provider(context)
        try {
            val base = VoicePrefs.baseUrl(context, provider) ?: throw LlmException("No server URL set")
            val key = VoicePrefs.apiKey(context, provider)
            val ctx = snap.toAssistantContext()
            var model = VoicePrefs.model(context, provider) ?: pick(context, provider, base, key, emptySet())
            val intent = try {
                LlmInterpreter.interpret(base, key, model, ctx, transcript)
            } catch (e: LlmInterpreter.ModelGone) {
                // Providers retire models; pick the newest one still offered and try once more.
                Log.w(TAG, "model $model retired, picking another")
                model = pick(context, provider, base, key, setOf(model))
                LlmInterpreter.interpret(base, key, model, ctx, transcript)
            }
            val t1 = System.currentTimeMillis()
            Resolver.resolve(transcript, intent, snap, null)
                .also { Log.i(TAG, "resolve ${System.currentTimeMillis() - t1} ms, total ${System.currentTimeMillis() - t0} ms") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "llm failed, using rules", e)
            val msg = (e as? LlmException)?.message ?: "Couldn't reach ${provider.label}"
            Resolver.resolve(transcript, rule.intent, snap, "$msg. Showing basic results.")
        }
    }

    private fun pick(context: Context, provider: Provider, base: String, key: String?, exclude: Set<String>): String =
        (LlmInterpreter.pickDefault(LlmInterpreter.models(base, key), provider.preferredModels, exclude)
            ?: throw LlmException("No model available"))
            .also { VoicePrefs.setModel(context, provider, it) }

    private fun clearlyResolved(r: VoiceResults): Boolean {
        val q = r.intent.query
        return when (val top = r.top) {
            is TopHit.App, is TopHit.YouTube, is TopHit.Resume -> true
            is TopHit.Title -> Resolver.score(q, top.meta.name) >= 0.85
            null -> false
        }
    }
}
