package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.ServerCapabilities
import com.nuvio.tv.domain.model.ServerConfiguration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Vibe fork: which Nuvio account backend this install uses. */
enum class AccountMode(val wire: String) {
    /** No account: nothing syncs, no sign-in offered. */
    NONE("none"),
    /** The official Nuvio service (api.nuvio.tv), found through its public discovery document. */
    OFFICIAL("official"),
    /** The household's own NuvioMedia/self-host server, or the backend baked into this build. */
    CUSTOM("custom");

    companion object {
        fun fromWire(value: String?): AccountMode? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

@Singleton
class ServerConfigurationStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val preferences get() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * The stored choice; before anything was chosen: a saved custom server or a backend baked into
     * the build counts as [AccountMode.CUSTOM] (so existing setups keep working), otherwise
     * [AccountMode.NONE].
     */
    fun accountMode(): AccountMode {
        if (!BuildConfig.FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED) {
            return if (hasBuildBackend()) AccountMode.CUSTOM else AccountMode.NONE
        }
        AccountMode.fromWire(preferences.getString(KEY_ACCOUNT_MODE, null))?.let { return it }
        return when {
            preferences.getBoolean(KEY_CUSTOM_ENABLED, false) -> AccountMode.CUSTOM
            hasBuildBackend() -> AccountMode.CUSTOM
            else -> AccountMode.NONE
        }
    }

    fun loadActive(): ServerConfiguration {
        if (!BuildConfig.FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED) {
            return if (hasBuildBackend()) buildConfiguration() else noAccountConfiguration()
        }
        return when (accountMode()) {
            AccountMode.NONE -> noAccountConfiguration()
            AccountMode.OFFICIAL -> storedServer()?.let { stored ->
                // Upstream's own sign-in pages: api.nuvio.tv has no /tv-login of its own.
                stored.copy(
                    isCustom = false,
                    tvLoginWebBaseUrl = OFFICIAL_TV_LOGIN_URL,
                    deviceLoginWebBaseUrl = OFFICIAL_DEVICE_LOGIN_URL
                )
            } ?: noAccountConfiguration()
            AccountMode.CUSTOM -> storedServer()
                ?: if (hasBuildBackend()) persistBuildServer() else noAccountConfiguration()
        }
    }

    private fun storedServer(): ServerConfiguration? {
        val prefs = preferences
        if (!prefs.getBoolean(KEY_CUSTOM_ENABLED, false)) return null
        val backendUrl = prefs.getString(KEY_BACKEND_URL, null)?.trim().orEmpty()
        val publishableKey = prefs.getString(KEY_PUBLISHABLE_KEY, null)?.trim().orEmpty()
        val emailPasswordAuth = prefs.getBoolean(KEY_EMAIL_PASSWORD_AUTH, false)
        val tvLogin = prefs.getBoolean(KEY_TV_LOGIN, false)
        if (backendUrl.isBlank() || publishableKey.isBlank() || (!emailPasswordAuth && !tvLogin)) return null
        return ServerConfiguration(
            backendUrl = backendUrl,
            publishableKey = publishableKey,
            capabilities = ServerCapabilities(
                emailPasswordAuth = emailPasswordAuth,
                tvLogin = tvLogin,
                watchParty = prefs.getBoolean(KEY_WATCH_PARTY, false)
            ),
            isCustom = true,
            discoveryUrl = prefs.getString(KEY_DISCOVERY_URL, null),
            tvLoginWebBaseUrl = prefs.getString(KEY_TV_LOGIN_URL, null)?.takeIf { it.isNotBlank() }
                ?: "$backendUrl/tv-login",
            // Self-host dashboards serve /tv-login only, so custom servers use that QR flow
            // (a persisted build server keeps whatever its build used).
            deviceLoginWebBaseUrl = prefs.getString(KEY_DEVICE_LOGIN_URL, null)?.takeIf { it.isNotBlank() },
            avatarPublicBaseUrl = prefs.getString(KEY_AVATAR_BASE_URL, null)?.takeIf { it.isNotBlank() }
                ?: "$backendUrl/storage/v1/object/public/avatars"
        )
    }

    /**
     * Vibe fork: saves the backend this build has baked in as an ordinary saved server, so an
     * update to a build without one (e.g. a CI release) keeps using it. Same URL and key, so the
     * signed-in session stays valid. Runs once: afterwards [storedServer] answers.
     */
    private fun persistBuildServer(): ServerConfiguration {
        val build = buildConfiguration()
        val saved = preferences
            .edit()
            .putString(KEY_ACCOUNT_MODE, AccountMode.CUSTOM.wire)
            .putBoolean(KEY_CUSTOM_ENABLED, true)
            .putString(KEY_BACKEND_URL, build.backendUrl)
            .putString(KEY_PUBLISHABLE_KEY, build.publishableKey)
            .putBoolean(KEY_EMAIL_PASSWORD_AUTH, build.capabilities.emailPasswordAuth)
            .putBoolean(KEY_TV_LOGIN, build.capabilities.tvLogin)
            .putBoolean(KEY_WATCH_PARTY, build.capabilities.watchParty)
            .putString(KEY_AVATAR_BASE_URL, build.avatarPublicBaseUrl)
            .putString(KEY_TV_LOGIN_URL, build.tvLoginWebBaseUrl)
            .putString(KEY_DEVICE_LOGIN_URL, build.deviceLoginWebBaseUrl)
            .commit()
        return (if (saved) storedServer() else null) ?: build
    }

    /** Saves a discovered self-host server as the active account backend. */
    fun saveCustom(configuration: ServerConfiguration): Boolean =
        saveServer(configuration, AccountMode.CUSTOM)

    /** Saves the discovered official server (api.nuvio.tv) as the active account backend. */
    fun saveOfficial(configuration: ServerConfiguration): Boolean =
        saveServer(configuration, AccountMode.OFFICIAL)

    /** No account: forget any server and offer no sign-in. */
    fun saveNoAccount(): Boolean {
        if (!BuildConfig.FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED) return false
        return preferences.edit().clear().putString(KEY_ACCOUNT_MODE, AccountMode.NONE.wire).commit()
    }

    private fun saveServer(configuration: ServerConfiguration, mode: AccountMode): Boolean {
        if (!BuildConfig.FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED) return false
        return preferences
            .edit()
            .putString(KEY_ACCOUNT_MODE, mode.wire)
            .putBoolean(KEY_CUSTOM_ENABLED, true)
            .putString(KEY_BACKEND_URL, configuration.backendUrl)
            .putString(KEY_PUBLISHABLE_KEY, configuration.publishableKey)
            .putBoolean(KEY_EMAIL_PASSWORD_AUTH, configuration.capabilities.emailPasswordAuth)
            .putBoolean(KEY_TV_LOGIN, configuration.capabilities.tvLogin)
            .putBoolean(KEY_WATCH_PARTY, configuration.capabilities.watchParty)
            .putString(KEY_DISCOVERY_URL, configuration.discoveryUrl)
            .putString(KEY_AVATAR_BASE_URL, configuration.avatarPublicBaseUrl)
            .remove(KEY_TV_LOGIN_URL)
            .remove(KEY_DEVICE_LOGIN_URL)
            .commit()
    }

    /** Back to the build's own default (its baked-in backend, or no account when it has none). */
    fun useOfficial(): Boolean =
        preferences
            .edit()
            .clear()
            .commit()

    fun hasBuildBackend(): Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    private fun buildConfiguration() = ServerConfiguration(
        backendUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
        publishableKey = BuildConfig.SUPABASE_ANON_KEY,
        capabilities = ServerCapabilities(
            // Self-hosted backends allow email/password sign-in (see /.well-known/nuvio).
            emailPasswordAuth = BuildConfig.SELF_HOSTED_BACKEND,
            tvLogin = true,
            watchParty = true
        ),
        isCustom = false,
        fallbackBackendUrl = BuildConfig.SUPABASE_FALLBACK_URL.trim().trimEnd('/').takeIf { it.isNotBlank() },
        tvLoginWebBaseUrl = BuildConfig.TV_LOGIN_WEB_BASE_URL,
        deviceLoginWebBaseUrl = BuildConfig.DEVICE_LOGIN_WEB_BASE_URL,
        avatarPublicBaseUrl = BuildConfig.AVATAR_PUBLIC_BASE_URL.trimEnd('/').takeIf { it.isNotBlank() }
    )

    /**
     * Inert placeholder: the Supabase client needs a URL, but with both sign-in capabilities off
     * nothing offers sign-in and nothing syncs.
     */
    private fun noAccountConfiguration() = ServerConfiguration(
        backendUrl = NO_ACCOUNT_BACKEND_URL,
        publishableKey = NO_ACCOUNT_PUBLISHABLE_KEY,
        capabilities = ServerCapabilities(emailPasswordAuth = false, tvLogin = false, watchParty = false),
        isCustom = false
    )

    companion object {
        const val OFFICIAL_BACKEND_URL = "https://api.nuvio.tv"
        const val OFFICIAL_TV_LOGIN_URL = "https://nuvio.tv/tv-login"
        const val OFFICIAL_DEVICE_LOGIN_URL = "https://nuvio.tv/link"
        const val NO_ACCOUNT_BACKEND_URL = "https://localhost"
        private const val NO_ACCOUNT_PUBLISHABLE_KEY = "no-account"

        private const val PREFERENCES_NAME = "server_configuration"
        private const val KEY_ACCOUNT_MODE = "vibe_account_mode"
        private const val KEY_CUSTOM_ENABLED = "custom_enabled"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_PUBLISHABLE_KEY = "publishable_key"
        private const val KEY_EMAIL_PASSWORD_AUTH = "email_password_auth"
        private const val KEY_TV_LOGIN = "tv_login"
        private const val KEY_WATCH_PARTY = "watch_party"
        private const val KEY_DISCOVERY_URL = "discovery_url"
        private const val KEY_AVATAR_BASE_URL = "avatar_base_url"
        private const val KEY_TV_LOGIN_URL = "tv_login_url"
        private const val KEY_DEVICE_LOGIN_URL = "device_login_url"
    }
}
