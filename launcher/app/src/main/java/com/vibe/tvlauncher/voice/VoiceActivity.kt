package com.vibe.tvlauncher.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.vibe.tvlauncher.data.AppRepo

import com.vibe.tvlauncher.data.Nuvio
import com.vibe.tvlauncher.data.voice.TopHit
import com.vibe.tvlauncher.data.voice.VoicePrefs
import com.vibe.tvlauncher.ui.LauncherTheme
import com.vibe.tvlauncher.ui.launch

/**
 * Google TV style assistant overlay. Opened by the remote's mic button (as the digital assistant),
 * by ASSIST / VOICE_COMMAND intents, or by the launcher's search button (typing mode).
 *
 * Setup over adb: `am start -n com.vibe.tvlauncher/.voice.VoiceActivity --es provider groq --es apiKey <key>`.
 */
class VoiceActivity : ComponentActivity() {
    private val vm: VoiceViewModel by viewModels()

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startListening() else Toast.makeText(this, "Microphone permission denied. You can type instead.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.onAutoRun = { vm.results?.top?.let { run(it) } }
        setContent {
            LauncherTheme {
                VoiceScreen(
                    vm = vm,
                    startTyping = intent?.getBooleanExtra(EXTRA_TYPE, false) == true,
                    onMic = ::listen,
                    onRun = ::run,
                    onClose = ::finish,
                )
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        // Optional setup over adb (same as the gear-icon settings).
        intent?.getStringExtra("provider")?.let { VoicePrefs.setProvider(this, it) }
        val p = VoicePrefs.provider(this)
        intent?.getStringExtra("baseUrl")?.let { VoicePrefs.setCustomUrl(this, it) }
        intent?.getStringExtra("apiKey")?.let {
            VoicePrefs.setApiKey(this, p, it)
            Toast.makeText(this, "${p.label} key saved on this TV", Toast.LENGTH_SHORT).show()
        }
        intent?.getStringExtra("model")?.let { VoicePrefs.setModel(this, p, it) }
        val query = intent?.getStringExtra(EXTRA_QUERY) ?: intent?.getStringExtra(android.app.SearchManager.QUERY)
        when {
            !query.isNullOrBlank() -> vm.submit(query)
            intent?.getBooleanExtra(EXTRA_TYPE, false) == true -> Unit
            else -> listen()
        }
    }

    private fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startListening()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Any key stops an auto-play countdown so the user can pick something else.
            if (vm.countdown != null && event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && event.keyCode != KeyEvent.KEYCODE_ENTER) {
                vm.cancelCountdown()
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST -> {
                    if (event.repeatCount == 0) listen()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        vm.cancelListening()
        vm.cancelCountdown()
    }

    /** Runs a result and closes the overlay. */
    fun run(hit: TopHit) {
        vm.cancelCountdown()
        Log.i("VibeVoice", "run $hit")
        when (hit) {
            is TopHit.Resume -> launch(Nuvio.resumeIntent(hit.item))
            is TopHit.Title -> launch(titleIntent(hit))
            is TopHit.App -> launch(hit.app.intent)
            is TopHit.YouTube -> launch(youtubeSearchIntent(this, hit.query))
        }
        finish()
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_QUERY = "query"

        fun listenIntent(context: Context): Intent =
            Intent(context, VoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** The launcher's search button: same screen, keyboard first. */
        fun typeIntent(context: Context): Intent = listenIntent(context).putExtra(EXTRA_TYPE, true)

        /**
         * "Play" opens straight into Nuvio's stream picker for movies (and for a named episode);
         * other titles open their detail page.
         */
        fun titleIntent(hit: TopHit.Title): Intent {
            val m = hit.meta
            if (!hit.play) return Nuvio.detailIntent(m)
            // Nuvio fork: `?playOnLoad=1` goes straight to stream selection; for series it picks the
            // next unwatched episode unless both season and episode are given.
            val ep = if (hit.season != null) "&season=${hit.season}&episode=${hit.episode ?: 1}" else ""
            val uri = Uri.parse("nuvio://detail/${m.type}/${Uri.encode(m.id)}?playOnLoad=1$ep")
            return Intent(Intent.ACTION_VIEW, uri).setPackage(Nuvio.PACKAGE)
        }

        fun youtubeSearchIntent(context: Context, query: String): Intent {
            val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
            return Intent(Intent.ACTION_VIEW, uri).apply { AppRepo.youtubePackage(context)?.let { setPackage(it) } }
        }
    }
}
