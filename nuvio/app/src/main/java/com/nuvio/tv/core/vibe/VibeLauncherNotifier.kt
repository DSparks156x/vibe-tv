package com.nuvio.tv.core.vibe

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells observers of [VibeLauncherProvider] (the Vibe TV launcher registers a ContentObserver)
 * when Continue Watching, the library or the active profile changed, so its rows update live.
 */
@Singleton
class VibeLauncherNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val watchProgressRepository: WatchProgressRepository,
    private val libraryRepository: LibraryRepository,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authority get() = "${context.packageName}.launcher"

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            // The provider reads the CW enrichment cache, so its version bump is the main signal.
            // Saved progress is also watched in case the cache pipeline isn't running.
            merge(
                cwEnrichmentCache.snapshotVersion.dropWhile { it == 0 }.map { },
                watchProgressRepository.allProgress
                    .map { list -> list.sumOf { it.lastWatched xor it.position } to list.size }
                    .distinctUntilChanged()
                    .drop(1)
                    .map { }
            )
                .debounce(DEBOUNCE_MS)
                .collect { notify("continue_watching") }
        }
        scope.launch {
            libraryRepository.libraryItems
                .map { items -> items.map { it.id } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(DEBOUNCE_MS)
                .collect { notify("library") }
        }
        scope.launch {
            // A profile switch changes every path's data.
            profileManager.activeProfileId
                .drop(1)
                .collect { notifyRoot() }
        }
    }

    /** Notifies every path (observers registered on the authority root or any child URI). */
    fun notifyRoot() = notify("")

    private fun notify(path: String) {
        runCatching {
            context.contentResolver.notifyChange(Uri.parse("content://$authority/$path"), null)
        }.onFailure { Log.w(TAG, "notifyChange($path) failed", it) }
    }

    private companion object {
        const val TAG = "VibeLauncherNotifier"
        const val DEBOUNCE_MS = 1_500L
    }
}
