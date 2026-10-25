package com.nuvio.tv.core.vibe

import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService.LauncherCwEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.WatchProgress

/**
 * The write side of [VibeLauncherProvider.call], kept free of Android types so it can be unit tested.
 * Arguments arrive as a plain map (from the call() extras), results go back as [Result].
 */
class VibeLauncherActions(private val backend: Backend) {

    /** What the actions need from Nuvio; the provider implements it over the real repositories. */
    interface Backend {
        suspend fun isInLibrary(id: String, type: String): Boolean
        /** Toggles the default library membership. Returns false if Nuvio needs the user to confirm. */
        suspend fun toggleLibrary(item: LibraryEntryInput): Boolean
        suspend fun continueWatching(): List<LauncherCwEntry>
        suspend fun markCompleted(progress: WatchProgress)
        suspend fun dismissNextUp(dismissKey: String)
        suspend fun removeProgress(contentId: String, season: Int?, episode: Int?)
        fun profileIds(): Set<Int>
        /** True/false when known, null when the lock state could not be checked. */
        suspend fun isProfileLocked(profileId: Int): Boolean?
        suspend fun setActiveProfile(profileId: Int)
    }

    data class Result(val ok: Boolean, val error: String? = null, val inLibrary: Boolean? = null) {
        companion object {
            val OK = Result(ok = true)
            fun fail(error: String) = Result(ok = false, error = error)
        }
    }

    suspend fun run(method: String, arg: String?, args: Map<String, Any?>): Result = when (method) {
        LIBRARY_CONTAINS -> libraryContains(args)
        LIBRARY_TOGGLE -> libraryToggle(args)
        MARK_WATCHED -> markWatched(args)
        REMOVE_PROGRESS -> removeProgress(args)
        SET_PROFILE -> setProfile(arg)
        else -> Result.fail("unknown method $method")
    }

    private suspend fun libraryContains(args: Map<String, Any?>): Result {
        val id = args.string("id") ?: return Result.fail("missing id")
        val type = args.string("type") ?: return Result.fail("missing type")
        return Result(ok = true, inLibrary = backend.isInLibrary(id, type))
    }

    private suspend fun libraryToggle(args: Map<String, Any?>): Result {
        val id = args.string("id") ?: return Result.fail("missing id")
        val type = args.string("type") ?: return Result.fail("missing type")
        val wasInLibrary = backend.isInLibrary(id, type)
        val applied = backend.toggleLibrary(
            LibraryEntryInput(
                itemId = id,
                itemType = type,
                title = args.string("name") ?: id,
                poster = args.string("poster"),
                background = args.string("background"),
                logo = args.string("logo")
            )
        )
        // Removing a title that is also on a tracking service (Trakt/Simkl) needs a confirmation
        // inside Nuvio, so that case is reported instead of forced.
        if (!applied) return Result(ok = false, error = "needs confirmation in Nuvio", inLibrary = wasInLibrary)
        return Result(ok = true, inLibrary = !wasInLibrary)
    }

    private suspend fun markWatched(args: Map<String, Any?>): Result {
        val id = args.string("id") ?: return Result.fail("missing id")
        val type = args.string("type") ?: return Result.fail("missing type")
        val season = args.int("season")
        val episode = args.int("episode")
        if (type == "series" && (season == null || episode == null)) {
            return Result.fail("season and episode required for series")
        }
        // Reuse the Continue Watching row for title and art when the launcher marks one of those.
        val row = backend.continueWatching()
            .map { it.progress }
            .firstOrNull { it.contentId == id && (season == null || (it.season == season && it.episode == episode)) }
        val duration = row?.duration?.takeIf { it > 0 } ?: 1L
        backend.markCompleted(
            WatchProgress(
                contentId = id,
                contentType = type,
                name = row?.name ?: args.string("name") ?: id,
                poster = row?.poster,
                backdrop = row?.backdrop,
                logo = row?.logo,
                videoId = args.string("video_id") ?: row?.videoId ?: id,
                season = season,
                episode = episode,
                episodeTitle = row?.episodeTitle,
                position = duration,
                duration = duration,
                lastWatched = System.currentTimeMillis(),
                progressPercent = 100f
            )
        )
        return Result.OK
    }

    private suspend fun removeProgress(args: Map<String, Any?>): Result {
        val id = args.string("id") ?: return Result.fail("missing id")
        val dismissKey = backend.continueWatching()
            .firstOrNull { it.progress.contentId == id }
            ?.dismissKey
        if (dismissKey != null) {
            // Next-up rows have no saved progress of their own: dismiss them like Nuvio's home does.
            backend.dismissNextUp(dismissKey)
        } else {
            backend.removeProgress(id, args.int("season"), args.int("episode"))
        }
        return Result.OK
    }

    private suspend fun setProfile(arg: String?): Result {
        val profileId = arg?.trim()?.toIntOrNull() ?: return Result.fail("missing profile id")
        if (profileId !in backend.profileIds()) return Result.fail("unknown profile")
        // Never switch into a PIN-locked profile from outside Nuvio, and fail closed when unsure.
        when (backend.isProfileLocked(profileId)) {
            true -> return Result.fail("profile is PIN locked")
            null -> return Result.fail("could not check the profile lock")
            false -> Unit
        }
        backend.setActiveProfile(profileId)
        return Result.OK
    }

    private fun Map<String, Any?>.string(key: String): String? =
        (get(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

    /** Accepts Int, Long or String values, since callers such as `adb shell content call` differ. */
    private fun Map<String, Any?>.int(key: String): Int? = when (val v = get(key)) {
        is Int -> v
        is Long -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    companion object {
        const val LIBRARY_CONTAINS = "library_contains"
        const val LIBRARY_TOGGLE = "library_toggle"
        const val MARK_WATCHED = "mark_watched"
        const val REMOVE_PROGRESS = "remove_progress"
        const val SET_PROFILE = "set_profile"
    }
}
