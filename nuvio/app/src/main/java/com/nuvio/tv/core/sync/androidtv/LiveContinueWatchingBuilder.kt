package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService.LauncherCwEntry
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.WatchProgress

/**
 * Vibe fork: builds the launcher's Continue Watching rows from live watch progress.
 *
 * Nuvio's enrichment cache is only rebuilt while its home screen is open, so a just-started
 * episode or the next-up after a finished one used to be missing until Nuvio was opened. Saved
 * progress already carries the title, art and episode, so in-progress rows come straight from it
 * (the cache only adds episode thumbnails). Next-up rows reuse the cache when it matches the
 * latest finished episode and otherwise ask [nextEpisode] (series meta, fetched on demand).
 */
class LiveContinueWatchingBuilder(
    private val nextEpisode: suspend (contentId: String, season: Int, episode: Int) -> NextEpisode?
) {
    /** The episode after a finished one, as resolved from the series meta. */
    data class NextEpisode(
        val videoId: String,
        val season: Int,
        val episode: Int,
        val title: String?,
        val thumbnail: String?,
        val name: String?,
        val poster: String?,
        val backdrop: String?,
        val logo: String?
    )

    suspend fun build(
        live: List<WatchProgress>,
        inProgressCache: List<CachedInProgressItem>,
        nextUpCache: List<CachedNextUpItem>,
        daysCap: Int?,
        dismissedNextUp: Set<String>,
        useEpisodeThumbnails: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): List<LauncherCwEntry> {
        val cutoffMs = daysCap?.let { nowMs - it.toLong() * DAY_MS }
        val latestPerTitle = live
            .filter { cutoffMs == null || it.lastWatched >= cutoffMs }
            .groupBy { it.contentId }
            .mapNotNull { (_, items) -> items.maxByOrNull(WatchProgress::lastWatched) }

        data class Sortable(val entry: LauncherCwEntry, val sortKey: Long)

        val rows = latestPerTitle.mapNotNull { p ->
            when {
                p.isInProgress() -> {
                    val cached = inProgressCache.firstOrNull {
                        it.contentId == p.contentId && it.season == p.season && it.episode == p.episode
                    }
                    val thumb = cached?.episodeThumbnail
                    val backdrop = if (useEpisodeThumbnails) {
                        thumb ?: p.backdrop ?: cached?.backdrop
                    } else {
                        p.backdrop ?: cached?.backdrop ?: thumb
                    }
                    Sortable(
                        LauncherCwEntry(
                            progress = p.copy(
                                poster = p.poster ?: cached?.poster,
                                backdrop = backdrop,
                                logo = p.logo ?: cached?.logo,
                                episodeTitle = p.episodeTitle ?: cached?.episodeTitle
                            ),
                            kind = LauncherCwEntry.KIND_IN_PROGRESS,
                            thumbnail = thumb
                        ),
                        p.lastWatched
                    )
                }
                p.isCompleted() && p.season != null && p.episode != null -> {
                    if (isDismissed(p.contentId, p.season, p.episode, dismissedNextUp)) return@mapNotNull null
                    nextUpFor(p, nextUpCache, useEpisodeThumbnails)?.let { Sortable(it, p.lastWatched) }
                }
                else -> null
            }
        }
        return rows.sortedByDescending { it.sortKey }.map { it.entry }
    }

    private suspend fun nextUpFor(
        finished: WatchProgress,
        nextUpCache: List<CachedNextUpItem>,
        useEpisodeThumbnails: Boolean
    ): LauncherCwEntry? {
        val season = finished.season ?: return null
        val episode = finished.episode ?: return null
        val cached = nextUpCache.firstOrNull {
            it.contentId == finished.contentId && it.seedSeason == season && it.seedEpisode == episode
        }
        if (cached != null) {
            if (!cached.hasAired) return null
            val image = if (useEpisodeThumbnails) cached.thumbnail ?: cached.backdrop else cached.backdrop ?: cached.thumbnail
            return nextUpEntry(
                finished, cached.videoId, cached.season, cached.episode, cached.episodeTitle,
                cached.thumbnail, cached.name, cached.poster, image, cached.logo
            )
        }
        val next = nextEpisode(finished.contentId, season, episode) ?: return null
        val image = if (useEpisodeThumbnails) next.thumbnail ?: next.backdrop else next.backdrop ?: next.thumbnail
        return nextUpEntry(
            finished, next.videoId, next.season, next.episode, next.title,
            next.thumbnail, next.name, next.poster, image, next.logo
        )
    }

    private fun nextUpEntry(
        finished: WatchProgress,
        videoId: String,
        season: Int,
        episode: Int,
        title: String?,
        thumbnail: String?,
        name: String?,
        poster: String?,
        backdrop: String?,
        logo: String?
    ) = LauncherCwEntry(
        progress = WatchProgress(
            contentId = finished.contentId,
            contentType = finished.contentType,
            name = name ?: finished.name,
            poster = poster ?: finished.poster,
            backdrop = backdrop ?: finished.backdrop,
            logo = logo ?: finished.logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = title,
            position = 0,
            duration = 0,
            lastWatched = finished.lastWatched
        ),
        kind = LauncherCwEntry.KIND_NEXT_UP,
        thumbnail = thumbnail,
        // Same key Nuvio's home uses for "remove from Continue Watching" on a next-up row.
        dismissKey = finished.contentId.trim()
    )

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        /** Nuvio's home dismisses by content id; the older channel code used "id_sXeY". Accept both. */
        fun isDismissed(contentId: String, seedSeason: Int?, seedEpisode: Int?, dismissed: Set<String>): Boolean {
            if (contentId.trim() in dismissed) return true
            val legacy = buildString {
                append(contentId)
                if (seedSeason != null) {
                    append("_s$seedSeason")
                    if (seedEpisode != null) append("e$seedEpisode")
                }
            }
            return legacy in dismissed
        }
    }
}
