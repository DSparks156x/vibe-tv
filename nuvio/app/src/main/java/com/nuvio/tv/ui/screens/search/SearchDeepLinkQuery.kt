package com.nuvio.tv.ui.screens.search

import kotlinx.coroutines.flow.MutableStateFlow

/** Query handed over by a `nuvio://search?q=` deep link; the Search screen consumes it. */
object SearchDeepLinkQuery {
    val pending = MutableStateFlow<String?>(null)
}
