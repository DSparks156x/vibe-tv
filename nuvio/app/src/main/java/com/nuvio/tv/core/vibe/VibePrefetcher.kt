package com.nuvio.tv.core.vibe

import android.util.Log
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.mediaserver.ServerStreams
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Vibe fork: warms what pressing play on a launcher card will need, while the card is focused.
 * The source search runs through [StreamRepository] with the same type / video id / season /
 * episode the Stream screen requests for that card, so the Stream screen attaches to this search
 * (StreamSearchSessionCache, 15 min) instead of starting its own. With `resolve`, the stream the
 * Stream screen would auto-pick is also resolved through debrid (DirectDebridResolver, 15 min).
 * Never touches playback or the torrent engine; ignored while the player is open.
 */
@Singleton
class VibePrefetcher @Inject constructor(
    private val streamRepository: StreamRepository,
    private val metaRepository: MetaRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val addonRepository: AddonRepository,
    private val serverStreams: ServerStreams,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val directDebridResolver: DirectDebridResolver,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var current: Job? = null
    private val recent = LinkedHashMap<String, Long>()

    /** Starts warming [request] and returns at once; a newer request cancels this one. */
    fun prefetch(request: VibeLauncherActions.PrefetchRequest) {
        if (PlayerSessionTracker.isOpen) {
            Log.i(TAG, "ignored ${request.videoId}: player open")
            return
        }
        val key = "${profileManager.activeProfileId.value}|${request.videoId}|${request.resolve}"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            recent.entries.removeAll { now - it.value >= THROTTLE_MS }
            if (recent.containsKey(key)) {
                Log.i(TAG, "skipped ${request.videoId}: prefetched recently")
                return
            }
            recent[key] = now
            current?.cancel()
            current = scope.launch {
                try {
                    run(request)
                } catch (error: CancellationException) {
                    // A newer card took focus; let a later focus on this one try again.
                    synchronized(lock) { recent.remove(key) }
                    throw error
                } catch (error: Exception) {
                    synchronized(lock) { recent.remove(key) }
                    Log.w(TAG, "prefetch ${request.videoId} failed", error)
                }
            }
        }
    }

    private suspend fun run(request: VibeLauncherActions.PrefetchRequest) {
        Log.i(TAG, "prefetch ${request.type} ${request.videoId} resolve=${request.resolve}")
        scope.launch {
            runCatching {
                withTimeoutOrNull(META_TIMEOUT_MS) {
                    metaRepository.getMetaFromAllAddons(request.type, request.contentId)
                        .first { it !is NetworkResult.Loading }
                }
            }
        }
        var streams: List<AddonStreams> = emptyList()
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            streamRepository.getStreamsFromAllAddons(
                type = request.type,
                videoId = request.videoId,
                season = request.season,
                episode = request.episode
            ).collect { result -> if (result is NetworkResult.Success) streams = result.data }
        }
        Log.i(TAG, "search ${request.videoId}: ${streams.sumOf { it.streams.size }} streams")
        if (!request.resolve || PlayerSessionTracker.isOpen) return

        val pick = autoPick(request, streams) ?: run {
            Log.i(TAG, "resolve ${request.videoId}: nothing auto-picks")
            return
        }
        // Media-server and plain HTTP picks already carry a playable URL; only debrid needs a lookup.
        if (!directDebridResolver.shouldResolveToPlayableStream(pick)) {
            Log.i(TAG, "resolve ${request.videoId}: pick needs no resolving")
            return
        }
        val result = directDebridResolver.resolve(pick, request.season, request.episode)
        Log.i(TAG, "resolve ${request.videoId}: ${result.javaClass.simpleName}")
    }

    /** The stream StreamScreenViewModel would auto-play from [groups], or null when it would wait for the user. */
    private suspend fun autoPick(request: VibeLauncherActions.PrefetchRequest, groups: List<AddonStreams>): Stream? {
        val settings = playerSettingsDataStore.playerSettings.first()
        val installedOrder = if (serverStreams.isNativeRequest(request.videoId)) {
            emptyList()
        } else {
            addonRepository.getInstalledAddons().first().enabledAddons().map { it.displayName }
        }
        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            groups,
            installedOrder,
            serverStreams.preferredSourceNames(request.type, request.videoId)
        ).flatMap { it.streams }
        val bingeGroup = if (settings.streamAutoPlayPreferBingeGroupForNextEpisode && settings.streamAutoPlayReuseBingeGroup) {
            bingeGroupCacheDataStore.get(request.contentId)
        } else {
            null
        }
        return pickStream(ordered, settings, installedOrder.toSet(), bingeGroup)
    }

    internal companion object {
        const val TAG = "VibePrefetch"
        const val THROTTLE_MS = 15 * 60 * 1_000L
        const val SEARCH_TIMEOUT_MS = 60_000L
        const val META_TIMEOUT_MS = 20_000L

        /** Same order as StreamScreenViewModel: the remembered binge group first, then the auto-play mode. */
        fun pickStream(
            streams: List<Stream>,
            settings: PlayerSettings,
            installedAddonNames: Set<String>,
            bingeGroup: String?
        ): Stream? {
            fun select(bingeGroupOnly: Boolean) = StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = settings.streamAutoPlayMode,
                regexPattern = settings.streamAutoPlayRegex,
                source = settings.streamAutoPlaySource,
                installedAddonNames = installedAddonNames,
                selectedAddons = settings.streamAutoPlaySelectedAddons,
                selectedPlugins = settings.streamAutoPlaySelectedPlugins,
                preferredBingeGroup = bingeGroup,
                preferBingeGroupInSelection = bingeGroup != null,
                bingeGroupOnly = bingeGroupOnly
            )
            val bingeMatch = if (bingeGroup != null) select(bingeGroupOnly = true) else null
            return bingeMatch ?: if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) null else select(false)
        }
    }
}
