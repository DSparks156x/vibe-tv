package com.vibe.tvlauncher.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

private const val TAG = "VibeVoice"

/**
 * Lets Vibe TV be picked as the system's digital assistant, so the remote's mic button
 * (KEYCODE_ASSIST, delivered by the TV's remote service) opens our assistant instead of Gemini.
 */
class VibeVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "voice interaction service ready")
    }

    // Google TV's remote service may start the active assistant service directly with the
    // button press; treat that like a session request.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "assistant service started: $intent extras=${intent?.extras?.keySet()}")
        if (intent != null && intent.action != SERVICE_INTERFACE) {
            runCatching { showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST) }
                .onFailure { startActivity(VoiceActivity.listenIntent(this)) }
        }
        return START_NOT_STICKY
    }
}

class VibeVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = VibeVoiceSession(this)
}

/** No UI of its own: hands straight over to [VoiceActivity]. */
class VibeVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "session shown flags=$showFlags args=${args?.keySet()}")
        val intent = VoiceActivity.listenIntent(context)
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startAssistantActivity failed, falling back", e)
            context.startActivity(intent)
        }
        hide()
    }
}

/** Speech recognizers that exist on Google TV / Android TV, best first. */
object Recognizers {
    val CANDIDATES = listOf(
        ComponentName("com.google.android.katniss", "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService"),
        ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"),
        ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"),
    )

    /** Installed recognition services, ours excluded (it only proxies to these). */
    fun available(context: Context): List<ComponentName> {
        val installed = context.packageManager
            .queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        return CANDIDATES.filter { it in installed } + installed.filter { it !in CANDIDATES }
    }
}
