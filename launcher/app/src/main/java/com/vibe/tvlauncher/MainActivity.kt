package com.vibe.tvlauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.vibe.tvlauncher.ui.LauncherApp
import com.vibe.tvlauncher.ui.LauncherTheme

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleExtras(intent)
        setContent {
            LauncherTheme {
                LauncherApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while we're already in front: jump back to the top of Home.
        vm.homePressed()
        handleExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.reloadApps()
        vm.refresh()
    }

    /** Debug/config extras, e.g. `adb shell am start -n com.vibe.tvlauncher/.MainActivity --es location "lat,lon"`. */
    private fun handleExtras(intent: Intent?) {
        intent?.getStringExtra("avatarUrl")?.let { vm.setAvatarUrl(it) }
        intent?.getStringExtra("location")?.let { vm.setLocation(it) }
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_PROFILES) vm.openProfiles()
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_PROFILES = "profiles"
    }
}
