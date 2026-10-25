package com.vibe.tvlauncher.voice

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.tvlauncher.data.voice.Assistant
import com.vibe.tvlauncher.data.voice.VoiceResults
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Phase { IDLE, LISTENING, THINKING, RESULTS, ERROR }

class VoiceViewModel(app: Application) : AndroidViewModel(app) {
    var phase by mutableStateOf(Phase.IDLE); private set
    /** Live words while listening; the final transcript afterwards. */
    var transcript by mutableStateOf(""); private set
    var level by mutableFloatStateOf(0f); private set
    var results by mutableStateOf<VoiceResults?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    /** 0..1 progress of the auto-run countdown, null when none is running. */
    var countdown by mutableStateOf<Float?>(null); private set

    /** Set by the activity: runs the top hit (and closes the overlay). */
    var onAutoRun: (() -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var candidates: List<ComponentName> = emptyList()
    private var candidateIndex = 0
    private var gotSpeech = false
    private var work: Job? = null
    private var countdownJob: Job? = null

    fun startListening() {
        cancelCountdown()
        work?.cancel()
        error = null
        transcript = ""
        level = 0f
        if (candidates.isEmpty()) candidates = Recognizers.available(getApplication())
        candidateIndex = 0
        if (candidates.isEmpty() && !SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            fail("Speech recognition isn't available on this TV. Type instead.")
            return
        }
        listenWith(candidates.getOrNull(candidateIndex))
    }

    private fun listenWith(component: ComponentName?) {
        recognizer?.destroy()
        val ctx = getApplication<Application>()
        val r = if (component != null) SpeechRecognizer.createSpeechRecognizer(ctx, component) else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        gotSpeech = false
        Log.i(TAG, "listening with ${component ?: "default recognizer"}")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { phase = Phase.LISTENING }
            override fun onBeginningOfSpeech() { gotSpeech = true }
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { if (it.isNotBlank()) transcript = it }
            }
            override fun onResults(results: Bundle?) {
                val text = best(results)?.takeIf { it.isNotBlank() } ?: transcript
                if (text.isBlank()) fail("Didn't catch that. Press the mic to try again.") else submit(text)
            }
            override fun onError(error: Int) {
                Log.w(TAG, "recognizer $component error $error")
                val retryable = error in setOf(
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                )
                if (retryable && !gotSpeech && candidateIndex + 1 < candidates.size) {
                    candidateIndex++
                    listenWith(candidates[candidateIndex])
                    return
                }
                if (transcript.isNotBlank()) { submit(transcript); return }
                fail(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Press the mic to try again."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No connection for speech recognition"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed"
                        SpeechRecognizer.ERROR_AUDIO -> "Couldn't open the microphone"
                        else -> "Speech recognition failed ($error)"
                    },
                )
            }
        })
        phase = Phase.LISTENING
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName),
        )
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun cancelListening() {
        recognizer?.cancel()
        if (phase == Phase.LISTENING) phase = if (results != null) Phase.RESULTS else Phase.IDLE
    }

    /** Typed or spoken request. */
    fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        recognizer?.cancel()
        cancelCountdown()
        transcript = q
        level = 0f
        phase = Phase.THINKING
        work?.cancel()
        work = viewModelScope.launch {
            val r = runCatching { Assistant.handle(getApplication(), q) }
                .onFailure { Log.w(TAG, "assistant failed", it) }
                .getOrNull()
            if (r == null) { fail("Something went wrong. Try again."); return@launch }
            results = r
            phase = Phase.RESULTS
            if (r.autoRun && r.top != null) startCountdown()
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            val total = 2200L
            val step = 50L
            var t = 0L
            while (t < total) {
                countdown = t.toFloat() / total
                delay(step)
                t += step
            }
            countdown = null
            onAutoRun?.invoke()
        }
    }

    /** Any key press during the countdown keeps the results on screen instead. */
    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        countdown = null
    }

    private fun fail(message: String) {
        error = message
        phase = Phase.ERROR
        level = 0f
    }

    private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    override fun onCleared() {
        recognizer?.destroy()
        recognizer = null
        super.onCleared()
    }

    companion object { private const val TAG = "VibeVoice" }
}
