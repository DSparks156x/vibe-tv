package com.vibe.tvlauncher.keys

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.vibe.tvlauncher.MainActivity

/**
 * Catches the remote's profile button. Google TV routes it straight to the stock launcher, which is
 * disabled when Vibe is the home app, so no app ever sees it otherwise. Accessibility services get
 * keys before that routing. Only this one key is touched; everything else passes through.
 */
class RemoteKeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_PROFILE_SWITCH) return false
        if (event.action == KeyEvent.ACTION_UP) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PROFILES)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
