package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService.LauncherCwEntry
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveContinueWatchingBuilderTest {

    private val now = 1_800_000_000_000L
    private val lookups = mutableListOf<String>()

    private val builder = LiveContinueWatchingBuilder { id, s, e ->
        lookups += "$id:$s:$e"
        if (id == "tt-show") {
            LiveContinueWatchingBuilder.NextEpisode(
                videoId = "tt-show:$s:${e + 1}", season = s, episode = e + 1, title = "Ep ${e + 1}",
                thumbnail = "thumb", name = "Show", poster = "p", backdrop = "b", logo = "l"
            )
        } else null
    }

    private fun progress(
        id: String, type: String, season: Int?, episode: Int?, pos: Long, dur: Long, at: Long
    ) = WatchProgress(
        contentId = id, contentType = type, name = "Name $id", poster = "poster", backdrop = "backdrop",
        logo = null, videoId = "$id:$season:$episode", season = season, episode = episode,
        episodeTitle = null, position = pos, duration = dur, lastWatched = at
    )

    private fun build(live: List<WatchProgress>, nextUp: List<CachedNextUpItem> = emptyList(), dismissed: Set<String> = emptySet()) =
        runBlocking {
            builder.build(live, emptyList(), nextUp, daysCap = null, dismissedNextUp = dismissed,
                useEpisodeThumbnails = false, nowMs = now)
        }

    @Test
    fun startedEpisodeShowsWithoutCache() {
        val rows = build(listOf(progress("tt-show", "series", 1, 2, 60_000, 1_300_000, now - 1_000)))
        assertEquals(1, rows.size)
        assertEquals(LauncherCwEntry.KIND_IN_PROGRESS, rows[0].kind)
        assertEquals(2, rows[0].progress.episode)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun finishedEpisodeBecomesNextUpFromMeta() {
        val rows = build(
            listOf(
                progress("tt-show", "series", 1, 1, 1_000, 1_000, now - 5_000),
                progress("tt-show", "series", 1, 0, 1_000, 1_000, now - 9_000)
            )
        )
        assertEquals(1, rows.size)
        assertEquals(LauncherCwEntry.KIND_NEXT_UP, rows[0].kind)
        assertEquals(2, rows[0].progress.episode)
        assertEquals("tt-show", rows[0].dismissKey)
        assertEquals(listOf("tt-show:1:1"), lookups)
    }

    @Test
    fun latestProgressPerTitleWinsAndMoviesFinishOut() {
        val rows = build(
            listOf(
                progress("tt-show", "series", 1, 1, 1_000, 1_000, now - 9_000), // finished earlier
                progress("tt-show", "series", 1, 2, 300_000, 1_300_000, now - 1_000), // now watching E2
                progress("tt-movie", "movie", null, null, 9_000, 10_000, now - 2_000), // 90% = done
                progress("tt-film", "movie", null, null, 1_000, 10_000, now - 3_000)
            )
        )
        assertEquals(listOf("tt-show", "tt-film"), rows.map { it.progress.contentId })
        assertTrue(rows.all { it.kind == LauncherCwEntry.KIND_IN_PROGRESS })
    }

    @Test
    fun dismissedNextUpIsHiddenEitherKeyFormat() {
        val finished = listOf(progress("tt-show", "series", 1, 1, 1_000, 1_000, now - 1_000))
        assertTrue(build(finished, dismissed = setOf("tt-show")).isEmpty())
        assertTrue(build(finished, dismissed = setOf("tt-show_s1e1")).isEmpty())
    }

    @Test
    fun cachedNextUpIsUsedWithoutLookup() {
        val cached = CachedNextUpItem(
            contentId = "tt-show", contentType = "series", name = "Show", poster = null, backdrop = "bd",
            logo = null, videoId = "tt-show:1:2", season = 1, episode = 2, episodeTitle = "Two",
            thumbnail = "t2", lastWatched = now - 1_000, sortTimestamp = now - 1_000,
            seedSeason = 1, seedEpisode = 1
        )
        val rows = build(listOf(progress("tt-show", "series", 1, 1, 1_000, 1_000, now - 1_000)), listOf(cached))
        assertEquals("Two", rows.single().progress.episodeTitle)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun noNextEpisodeMeansNoRow() {
        assertTrue(build(listOf(progress("tt-ended", "series", 3, 10, 1_000, 1_000, now - 1_000))).isEmpty())
    }
}
