package com.nuvio.tv.core.vibe

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VibePrefetchTest {
    @Test
    fun parsesSeriesPrefetch() {
        val request = VibeLauncherActions.parsePrefetch(
            mapOf("type" to "series", "id" to "tt123", "video_id" to "tt123:1:2", "season" to 1, "episode" to 2L, "resolve" to true)
        )
        assertEquals(VibeLauncherActions.PrefetchRequest("series", "tt123", "tt123:1:2", 1, 2, true), request)
    }

    @Test
    fun movieDefaultsVideoIdAndIgnoresEpisode() {
        val request = VibeLauncherActions.parsePrefetch(
            mapOf("type" to "Movie", "id" to "tt9", "season" to 3, "resolve" to "false")
        )
        assertEquals(VibeLauncherActions.PrefetchRequest("movie", "tt9", "tt9", null, null, false), request)
    }

    @Test
    fun seriesWithoutEpisodeFallsBackToBuiltVideoIdOnlyWhenComplete() {
        assertNull(VibeLauncherActions.parsePrefetch(mapOf("type" to "series", "id" to "tt1", "season" to "1")))
        assertEquals(
            "tt1:1:4",
            VibeLauncherActions.parsePrefetch(mapOf("type" to "series", "id" to "tt1", "season" to "1", "episode" to "4"))?.videoId
        )
    }

    @Test
    fun rejectsBadExtras() {
        assertNull(VibeLauncherActions.parsePrefetch(mapOf("type" to "channel", "id" to "x")))
        assertNull(VibeLauncherActions.parsePrefetch(mapOf("type" to "movie")))
    }

    @Test
    fun manualPicksNothingWithoutBingeGroup() {
        val streams = listOf(stream("A 1080p"), stream("B 2160p"))
        assertNull(VibePrefetcher.pickStream(streams, PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL), setOf("Addon"), null))
        assertEquals(
            "A 1080p",
            VibePrefetcher.pickStream(streams, PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM), setOf("Addon"), null)?.name
        )
    }

    @Test
    fun parsesSettingValues() {
        assertEquals(PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED, VibeLauncherSettings.parseTimeout("unlimited"))
        assertEquals(10, VibeLauncherSettings.parseTimeout("10"))
        assertNull(VibeLauncherSettings.parseTimeout("11"))
        assertEquals("unlimited", VibeLauncherSettings.timeoutWire(PlayerSettings.STREAM_AUTOPLAY_TIMEOUT_UNLIMITED))
        assertEquals(true, VibeLauncherSettings.parseToggle("true"))
        assertEquals(false, VibeLauncherSettings.parseToggle("off"))
        assertNull(VibeLauncherSettings.parseToggle("maybe"))
    }

    private fun stream(name: String) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://example.invalid/$name",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null
    )
}
