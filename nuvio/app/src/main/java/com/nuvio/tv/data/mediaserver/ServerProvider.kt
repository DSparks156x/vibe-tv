package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds

enum class ServerCapability {
    SEARCH,
    EXTERNAL_ID_LOOKUP,
    USER_STATE_READ,
    USER_STATE_WRITE,
    TRANSCODING
}

interface ServerProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ServerCapability>
    val minimumVersion: String

    suspend fun signIn(address: String, username: String, password: String): ServerSignIn = unsupported()

    suspend fun libraries(session: ServerSession): List<ServerLibrary>

    suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle>

    suspend fun details(session: ServerSession, itemId: String): ServerItemDetails

    suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate>

    suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession

    suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) = Unit

    suspend fun signOut(session: ServerSession) = Unit

    suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> = unsupported()

    suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> = unsupported()

    suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> = unsupported()

    suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean): Unit = unsupported()

    suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> = unsupported()

    /** Items of [library] that may be [query]; null when the server cannot look up a single title this way. */
    suspend fun lookupTitle(
        session: ServerSession,
        library: ServerLibrary,
        query: ServerTitleQuery
    ): List<ServerIndexEntry>? = null

    suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int
    ): ServerEpisode? = unsupported()
}

/**
 * Vibe fork: providers that sign in through a code the user approves elsewhere (Plex's plex.tv/link)
 * instead of a username and password. The sign-in dialog drives these steps.
 */
interface PinSignInProvider {
    suspend fun createPin(): ServerPin

    /** The account token once [pin] is approved, null while it is still waiting. */
    suspend fun pollPin(pin: ServerPin): String?

    /** Servers the account can use. */
    suspend fun servers(accountToken: String): List<ServerChoice>

    /** Signs in to [server], picking a connection that answers (local first). */
    suspend fun signIn(accountToken: String, server: ServerChoice): ServerSignIn
}

class ServerPin(
    val id: String,
    val code: String,
    /** Page where the code is typed, shown as text. */
    val linkUrl: String,
    /** Page that approves this code directly, for the QR code. */
    val approveUrl: String,
    val expiresAtEpochMs: Long?
) {
    override fun toString(): String = "ServerPin(id=$id)"
}

data class ServerChoice(
    val id: String,
    val name: String,
    val owned: Boolean
)

data class ServerIndexEntry(
    val itemId: String,
    val ids: TrackingExternalIds,
    val year: Int? = null
)

data class ServerTitleQuery(
    val ids: TrackingExternalIds,
    val name: String?,
    val year: Int?,
    val originalName: String? = null
)

data class ServerEpisode(
    val itemId: String,
    val premiereDate: String?
)

fun ServerProvider.supports(capability: ServerCapability): Boolean = capability in capabilities

private fun unsupported(): Nothing = throw ServerException(ServerFailure.UNSUPPORTED)
