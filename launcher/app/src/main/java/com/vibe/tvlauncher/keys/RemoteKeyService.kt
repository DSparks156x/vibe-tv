package com.vibe.tvlauncher.keys

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.vibe.tvlauncher.MainActivity

/**
 * Catches the remote's profile and settings buttons. Google TV routes them to the stock launcher, which
 * is disabled when Vibe is the home app, so they'd do nothing. Accessibility services get keys before
 * that routing. Only these two keys are touched; everything else (Menu included) passes through.
 */
class RemoteKeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val intent = when (event.keyCode) {
            KeyEvent.KEYCODE_PROFILE_SWITCH ->
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PROFILES)
            KeyEvent.KEYCODE_SETTINGS -> Intent(Settings.ACTION_SETTINGS)
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_UP) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
