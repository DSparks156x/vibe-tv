package com.nuvio.tv.domain.deeplink

sealed interface AppDeepLink {
    data class Meta(
        val type: String,
        val id: String,
        /** Vibe fork: `?playOnLoad=1` goes straight to stream selection / auto-play. */
        val playOnLoad: Boolean = false,
        val season: Int? = null,
        val episode: Int? = null
    ) : AppDeepLink

    data class AddonInstall(
        val manifestUrl: String
    ) : AppDeepLink

    data class Party(
        val code: String
    ) : AppDeepLink

    /** nuvio://search?q=... (query optional: no query just opens the search screen). */
    data class Search(
        val query: String?
    ) : AppDeepLink
}
