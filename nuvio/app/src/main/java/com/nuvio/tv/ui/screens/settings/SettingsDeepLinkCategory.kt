package com.nuvio.tv.ui.screens.settings

import kotlinx.coroutines.flow.MutableStateFlow

/** Category handed over by a deep link (e.g. `nuvio://account`); the Settings screen consumes it. */
internal object SettingsDeepLinkCategory {
    val pending = MutableStateFlow<SettingsCategory?>(null)
}
