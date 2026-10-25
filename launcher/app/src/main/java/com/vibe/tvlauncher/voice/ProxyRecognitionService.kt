package com.vibe.tvlauncher.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * A digital assistant must name a recognition service, and Android makes it the system default.
 * This one forwards to the TV's real recognizer (Google's), so voice search in other apps keeps working.
 */
class ProxyRecognitionService : RecognitionService() {
    private var target: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        target?.destroy()
        val component = Recognizers.available(this).firstOrNull()
        if (component == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this, component)
        target = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { listener.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { listener.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { listener.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safe { buffer?.let { listener.bufferReceived(it) } }
            override fun onEndOfSpeech() = safe { listener.endOfSpeech() }
            override fun onError(error: Int) = safe { listener.error(error) }
            override fun onResults(results: Bundle?) = safe { listener.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { listener.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) {
        target?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        target?.cancel()
    }

    override fun onDestroy() {
        target?.destroy()
        target = null
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w("VibeVoice", "recognition proxy callback failed: ${e.message}")
        }
    }
}
