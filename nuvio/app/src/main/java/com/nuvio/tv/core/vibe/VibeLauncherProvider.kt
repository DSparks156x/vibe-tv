package com.nuvio.tv.core.vibe

import android.content.ContentProvider
import android.os.Bundle
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only data feed for the Vibe TV launcher (com.vibe.tvlauncher).
 *
 * Guarded by a signature permission, so only apps signed with the same key can read it.
 * Content URIs (authority = "<applicationId>.launcher"):
 *  - /continue_watching  same list as Nuvio's Android TV "Continue Watching" channel
 *  - /library            the active profile's library
 *  - /profile            the active profile (name, avatar)
 *  - /addons             installed addons with their catalogs (JSON)
 *  - /profiles           all profiles, is_active = 1 for the current one
 *
 * Writes go through [call] (same signature permission, enforced there; see [VibeLauncherActions]).
 * Observers of content://<authority>/ (notifyForDescendants) are notified after writes and when
 * Continue Watching, the library or the active profile change.
 */
class VibeLauncherProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VibeLauncherEntryPoint {
        fun channelSyncService(): AndroidTvChannelSyncService
        fun libraryRepository(): LibraryRepository
        fun addonRepository(): AddonRepository
        fun profileManager(): ProfileManager
        fun watchProgressRepository(): WatchProgressRepository
        fun traktSettingsDataStore(): TraktSettingsDataStore
        fun authManager(): AuthManager
        fun profileSyncService(): ProfileSyncService
        fun launcherNotifier(): VibeLauncherNotifier
        fun serverConfigurationStore(): com.nuvio.tv.data.local.ServerConfigurationStore
        fun serverDiscoveryService(): com.nuvio.tv.data.remote.ServerDiscoveryService
        fun appRestarter(): com.nuvio.tv.core.runtime.AppRestarter
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun onCreate(): Boolean {
        val authority = "${context!!.packageName}.launcher"
        matcher.addURI(authority, "continue_watching", CONTINUE_WATCHING)
        matcher.addURI(authority, "library", LIBRARY)
        matcher.addURI(authority, "profile", PROFILE)
        matcher.addURI(authority, "addons", ADDONS)
        matcher.addURI(authority, "profiles", PROFILES)
        matcher.addURI(authority, "history", HISTORY)
        return true
    }

    private fun entryPoint(): VibeLauncherEntryPoint =
        EntryPointAccessors.fromApplication(context!!.applicationContext, VibeLauncherEntryPoint::class.java)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = try {
        when (matcher.match(uri)) {
            CONTINUE_WATCHING -> continueWatching()
            LIBRARY -> library()
            PROFILE -> profile()
            ADDONS -> addons()
            PROFILES -> profiles()
            HISTORY -> history()
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "query failed for $uri", e)
        null
    }

    private fun continueWatching(): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "content_id", "content_type", "name", "video_id", "season", "episode", "episode_title",
                "position", "duration", "progress", "last_watched", "poster", "backdrop", "logo",
                "kind", "thumbnail", "remaining_ms"
            )
        )
        val entries = runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) { entryPoint().channelSyncService().launcherEntries() }
        }.orEmpty()
        entries.forEach { entry ->
            val p = entry.progress
            val fraction = when {
                p.progressPercent != null -> p.progressPercent / 100f
                p.duration > 0 -> p.position.toFloat() / p.duration
                else -> 0f
            }.coerceIn(0f, 1f)
            cursor.addRow(
                arrayOf<Any?>(
                    p.contentId, p.contentType, p.name, p.videoId, p.season, p.episode, p.episodeTitle,
                    p.position, p.duration, fraction, p.lastWatched, p.poster, p.backdrop, p.logo,
                    entry.kind, entry.thumbnail,
                    if (p.duration > 0) (p.duration - p.position).coerceAtLeast(0L) else null
                )
            )
        }
        return cursor
    }

    private fun library(): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "id", "type", "name", "poster", "background", "logo", "description",
                "release_info", "rating", "genres"
            )
        )
        val items = runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) { entryPoint().libraryRepository().libraryItems.first() }
        }.orEmpty()
        items.forEach { e ->
            cursor.addRow(
                arrayOf<Any?>(
                    e.id, e.type, e.name, e.poster, e.background, e.logo, e.description,
                    e.releaseInfo, e.imdbRating, e.genres.joinToString(",")
                )
            )
        }
        return cursor
    }

    private fun profile(): Cursor {
        val cursor = MatrixCursor(arrayOf("id", "name", "avatar_url", "avatar_color", "background_url"))
        entryPoint().profileManager().activeProfile?.let { p ->
            cursor.addRow(arrayOf<Any?>(p.id, p.name, p.avatarUrl, p.avatarColorHex, p.profileBackgroundUrl))
        }
        return cursor
    }

    private fun addons(): Cursor {
        val cursor = MatrixCursor(arrayOf("base_url", "name", "logo", "catalogs"))
        val addons = runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) { entryPoint().addonRepository().getInstalledAddons().first() }
        }.orEmpty()
        addons.forEach { a ->
            val catalogs = JSONArray()
            a.catalogs.forEach { c ->
                catalogs.put(
                    JSONObject()
                        .put("type", c.rawType)
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("showInHome", c.showInHome)
                        .put("extraRequired", JSONArray(c.extraRequired))
                )
            }
            cursor.addRow(arrayOf<Any?>(a.baseUrl, a.displayName, a.logo, catalogs.toString()))
        }
        return cursor
    }

    private fun profiles(): Cursor {
        val cursor = MatrixCursor(arrayOf("id", "name", "avatar_url", "avatar_color", "is_active"))
        val manager = entryPoint().profileManager()
        val activeId = manager.activeProfileId.value
        manager.profiles.value.forEach { p ->
            cursor.addRow(
                arrayOf<Any?>(p.id, p.name, p.avatarUrl, p.avatarColorHex, if (p.id == activeId) 1 else 0)
            )
        }
        return cursor
    }

    /** Watched titles (finished movies/episodes, one row per title), most recent first. */
    private fun history(): Cursor {
        val cursor = MatrixCursor(arrayOf("id", "type", "name", "poster", "lastWatchedMs"))
        val repo = entryPoint().watchProgressRepository()
        val (watched, progress) = runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                runCatching { repo.watchedItems.first() }.getOrDefault(emptyList()) to
                    runCatching { repo.allProgress.first() }.getOrDefault(emptyList())
            }
        } ?: (emptyList<com.nuvio.tv.domain.model.WatchedItem>() to emptyList())
        data class Row(val id: String, val type: String, val name: String, val poster: String?, val at: Long)
        val rows = watched.map { Row(it.contentId, it.contentType, it.title, it.poster, it.watchedAt) } +
            progress.filter { it.isCompleted() }.map { Row(it.contentId, it.contentType, it.name, it.poster, it.lastWatched) }
        rows.groupBy { it.id }
            .map { (_, items) ->
                val latest = items.maxBy { it.at }
                latest.copy(poster = latest.poster ?: items.firstNotNullOfOrNull { it.poster })
            }
            .sortedByDescending { it.at }
            .forEach { cursor.addRow(arrayOf<Any?>(it.id, it.type, it.name, it.poster, it.at)) }
        return cursor
    }

    private fun settings(): VibeLauncherSettings {
        val ep = entryPoint()
        return VibeLauncherSettings(
            context!!.applicationContext,
            ep.serverConfigurationStore(),
            ep.serverDiscoveryService(),
            ep.authManager(),
            ep.appRestarter()
        )
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // readPermission only guards query(); call() has to check the permission itself.
        context!!.enforceCallingPermission(PERMISSION, "Vibe launcher permission required")
        when (method) {
            VibeLauncherSettings.METHOD_GET -> return Bundle().apply {
                putString("json", runCatching { settings().getSettingsJson() }
                    .getOrElse { Log.w(TAG, "get_settings failed", it); "{\"fields\":[]}" })
            }
            VibeLauncherSettings.METHOD_SET -> {
                val values = extras?.keySet()?.associateWith { key -> extras.valueOf(key)?.toString() }.orEmpty()
                val json = runCatching {
                    runBlocking { withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { settings().setSettings(values) } }
                        ?: "{\"results\":{}}"
                }.getOrElse { Log.w(TAG, "set_settings failed", it); "{\"results\":{}}" }
                return Bundle().apply { putString("json", json) }
            }
        }
        val args = extras?.let { bundle -> bundle.keySet().associateWith { key -> bundle.valueOf(key) } }.orEmpty()
        val result = try {
            runBlocking {
                withTimeoutOrNull(TIMEOUT_MS) { VibeLauncherActions(backend()).run(method, arg, args) }
            } ?: VibeLauncherActions.Result.fail("timeout")
        } catch (e: Exception) {
            Log.w(TAG, "call($method) failed", e)
            VibeLauncherActions.Result.fail(e.message ?: e.javaClass.simpleName)
        }
        if (result.ok && method != VibeLauncherActions.LIBRARY_CONTAINS) {
            if (method == VibeLauncherActions.MARK_WATCHED || method == VibeLauncherActions.REMOVE_PROGRESS) {
                // Keep the Android TV channel / Watch Next in step with the launcher's change.
                runCatching {
                    runBlocking { withTimeoutOrNull(TIMEOUT_MS) { entryPoint().channelSyncService().reconcileFromCache() } }
                }.onFailure { Log.w(TAG, "channel refresh after $method failed", it) }
            }
            entryPoint().launcherNotifier().notifyRoot()
        }
        return Bundle().apply {
            putBoolean("ok", result.ok)
            result.error?.let { putString("error", it) }
            result.inLibrary?.let { putBoolean("in_library", it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun Bundle.valueOf(key: String): Any? = get(key)

    private fun backend(): VibeLauncherActions.Backend {
        val ep = entryPoint()
        return object : VibeLauncherActions.Backend {
            override suspend fun isInLibrary(id: String, type: String) =
                ep.libraryRepository().isInLibrary(id, type).first()

            override suspend fun toggleLibrary(item: LibraryEntryInput): Boolean =
                !ep.libraryRepository().toggleDefault(item).requiresRemovalConfirmation

            override suspend fun continueWatching() = ep.channelSyncService().launcherEntries()

            override suspend fun markCompleted(progress: WatchProgress) =
                ep.watchProgressRepository().markAsCompleted(progress)

            override suspend fun dismissNextUp(dismissKey: String) =
                ep.traktSettingsDataStore().addDismissedNextUpKey(dismissKey)

            override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) =
                ep.watchProgressRepository().removeProgress(contentId, season, episode)

            override fun profileIds() = ep.profileManager().profiles.value.mapTo(mutableSetOf()) { it.id }

            override suspend fun isProfileLocked(profileId: Int): Boolean? {
                // Profile PINs only exist on a signed-in Nuvio account.
                if (ep.authManager().authState.value !is AuthState.FullAccount) return false
                return ep.profileSyncService().pullProfileLockStates().getOrNull()?.let { it[profileId] == true }
            }

            override suspend fun setActiveProfile(profileId: Int) =
                ep.profileManager().setActiveProfile(profileId)
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "VibeLauncherProvider"
        const val TIMEOUT_MS = 4_000L
        const val CONTINUE_WATCHING = 1
        const val LIBRARY = 2
        const val PROFILE = 3
        const val ADDONS = 4
        const val PROFILES = 5
        const val HISTORY = 6
        // Server discovery for backend_url can take a few seconds.
        const val SETTINGS_TIMEOUT_MS = 20_000L
        const val PERMISSION = "com.vibe.permission.LAUNCHER_DATA"
    }
}
