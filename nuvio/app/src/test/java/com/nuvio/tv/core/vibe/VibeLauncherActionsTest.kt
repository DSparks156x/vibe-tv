package com.nuvio.tv.core.vibe

import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService.LauncherCwEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeLauncherActionsTest {

    private class FakeBackend : VibeLauncherActions.Backend {
        val library = mutableSetOf<Pair<String, String>>()
        var toggleNeedsConfirmation = false
        var cw = listOf<LauncherCwEntry>()
        val completed = mutableListOf<WatchProgress>()
        val dismissed = mutableListOf<String>()
        val removed = mutableListOf<Triple<String, Int?, Int?>>()
        val profiles = mutableSetOf(1, 2, 3)
        val locks = mutableMapOf<Int, Boolean?>(1 to false, 2 to false, 3 to true)
        var activeProfile = 1

        override suspend fun isInLibrary(id: String, type: String) = (id to type) in library
        override suspend fun toggleLibrary(item: LibraryEntryInput): Boolean {
            if (toggleNeedsConfirmation) return false
            val key = item.itemId to item.itemType
            if (!library.remove(key)) library.add(key)
            return true
        }
        override suspend fun continueWatching() = cw
        override suspend fun markCompleted(progress: WatchProgress) { completed += progress }
        override suspend fun dismissNextUp(dismissKey: String) { dismissed += dismissKey }
        override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
            removed += Triple(contentId, season, episode)
        }
        override fun profileIds() = profiles
        override suspend fun isProfileLocked(profileId: Int) = locks[profileId]
        override suspend fun setActiveProfile(profileId: Int) { activeProfile = profileId }
    }

    private val backend = FakeBackend()
    private val actions = VibeLauncherActions(backend)

    private fun run(method: String, arg: String? = null, vararg args: Pair<String, Any?>) =
        runBlocking { actions.run(method, arg, mapOf(*args)) }

    private fun progress(id: String, type: String, season: Int? = null, episode: Int? = null) = WatchProgress(
        contentId = id, contentType = type, name = "Name $id", poster = "p", backdrop = "b", logo = "l",
        videoId = "$id:${season ?: 0}:${episode ?: 0}", season = season, episode = episode,
        episodeTitle = "Ep", position = 600_000, duration = 3_600_000, lastWatched = 1L
    )

    @Test
    fun libraryContainsAndToggle() {
        assertEquals(false, run("library_contains", null, "id" to "tt1375666", "type" to "movie").inLibrary)

        val added = run("library_toggle", null, "id" to "tt1375666", "type" to "movie", "name" to "Inception")
        assertTrue(added.ok)
        assertEquals(true, added.inLibrary)
        assertEquals(true, run("library_contains", null, "id" to "tt1375666", "type" to "movie").inLibrary)

        val removed = run("library_toggle", null, "id" to "tt1375666", "type" to "movie")
        assertTrue(removed.ok)
        assertEquals(false, removed.inLibrary)
    }

    @Test
    fun libraryToggleReportsTrackingConfirmationInsteadOfForcing() {
        backend.library += "tt1" to "movie"
        backend.toggleNeedsConfirmation = true
        val result = run("library_toggle", null, "id" to "tt1", "type" to "movie")
        assertFalse(result.ok)
        assertEquals(true, result.inLibrary)
        assertTrue(("tt1" to "movie") in backend.library)
    }

    @Test
    fun missingArgumentsFail() {
        assertFalse(run("library_contains", null, "id" to "tt1").ok)
        assertFalse(run("mark_watched", null, "id" to "tt1", "type" to "series", "season" to 1).ok)
        assertFalse(run("nope").ok)
        assertTrue(backend.completed.isEmpty())
    }

    @Test
    fun markWatchedUsesContinueWatchingRowAndAcceptsStringNumbers() {
        backend.cw = listOf(LauncherCwEntry(progress("tt2", "series", 2, 5), "in_progress", "thumb"))
        val result = run("mark_watched", null, "id" to "tt2", "type" to "series", "season" to "2", "episode" to 5L)
        assertTrue(result.ok)
        val p = backend.completed.single()
        assertEquals("Name tt2", p.name)
        assertEquals("tt2:2:5", p.videoId)
        assertEquals(2, p.season)
        assertEquals(5, p.episode)
        assertEquals(p.duration, p.position)
        assertEquals(100f, p.progressPercent)
    }

    @Test
    fun markWatchedMovieWithoutRow() {
        assertTrue(run("mark_watched", null, "id" to "tt9", "type" to "movie").ok)
        val p = backend.completed.single()
        assertEquals("tt9", p.videoId)
        assertNull(p.season)
        assertEquals(100f, p.progressPercent)
    }

    @Test
    fun removeProgressDismissesNextUpButRemovesInProgress() {
        backend.cw = listOf(
            LauncherCwEntry(progress("tt3", "series", 1, 2), "next_up", null, dismissKey = "tt3_s1e1"),
            LauncherCwEntry(progress("tt4", "movie"), "in_progress", null)
        )
        assertTrue(run("remove_progress", null, "id" to "tt3", "season" to 1, "episode" to 2).ok)
        assertEquals(listOf("tt3_s1e1"), backend.dismissed)
        assertTrue(backend.removed.isEmpty())

        assertTrue(run("remove_progress", null, "id" to "tt4").ok)
        assertEquals(listOf(Triple("tt4", null as Int?, null as Int?)), backend.removed)
    }

    @Test
    fun setProfileRespectsPinLocks() {
        assertTrue(run("set_profile", "2").ok)
        assertEquals(2, backend.activeProfile)

        val locked = run("set_profile", "3")
        assertFalse(locked.ok)
        assertEquals(2, backend.activeProfile)

        backend.locks[1] = null // lock state unknown -> fail closed
        assertFalse(run("set_profile", "1").ok)
        assertFalse(run("set_profile", "9").ok)
        assertFalse(run("set_profile", null).ok)
        assertEquals(2, backend.activeProfile)
    }
}
