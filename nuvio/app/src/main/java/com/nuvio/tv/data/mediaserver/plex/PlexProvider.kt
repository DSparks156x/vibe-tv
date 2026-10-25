package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.PinSignInProvider
import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerCapability
import com.nuvio.tv.data.mediaserver.ServerChoice
import com.nuvio.tv.data.mediaserver.ServerEpisode
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerIndexEntry
import com.nuvio.tv.data.mediaserver.ServerItemDetails
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPage
import com.nuvio.tv.data.mediaserver.ServerPin
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerSignIn
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.buildUrl
import com.nuvio.tv.data.mediaserver.mediabrowser.pathSegment
import com.nuvio.tv.domain.model.Subtitle
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Vibe fork: Plex Media Server, signed in through plex.tv's PIN flow (plex.tv/link). Covers what the
 * Jellyfin/Emby providers do: libraries, browse, search, details, direct play with an HLS transcode
 * fallback, and progress / watched state back to the server.
 */
internal class PlexProvider(
    http: OkHttpClient,
    identity: ServerClientIdentity
) : ServerProvider, PinSignInProvider {
    override val id: String = "plex"
    override val displayName: String = "Plex"
    override val minimumVersion: String = "1.32"
    override val capabilities: Set<ServerCapability> = ServerCapability.entries.toSet()

    private val client = PlexClient(http, identity)
    private val resources = ConcurrentHashMap<String, PlexResource>()
    private val durations = ConcurrentHashMap<String, Long>()

    // ---- Sign-in (plex.tv) ----

    override suspend fun createPin(): ServerPin {
        // strong=false: the 4-character code is what plex.tv/link accepts.
        val pin = client.json.decodeFromString(
            PlexPin.serializer(),
            client.execute("POST", PlexClient.PLEX_TV, "/api/v2/pins", token = null, query = mapOf("strong" to "false"))
        )
        return ServerPin(
            id = pin.id.toString(),
            code = pin.code,
            linkUrl = LINK_URL,
            approveUrl = approveUrl(pin.code),
            expiresAtEpochMs = pin.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        )
    }

    internal fun approveUrl(code: String): String =
        "https://app.plex.tv/auth#?clientID=${client.clientId.urlPart()}&code=${code.urlPart()}" +
            "&context%5Bdevice%5D%5Bproduct%5D=${PlexClient.PRODUCT}"

    override suspend fun pollPin(pin: ServerPin): String? =
        client.get(PlexClient.PLEX_TV, "/api/v2/pins/${pathSegment(pin.id)}", PlexPin.serializer(), token = null)
            .authToken
            ?.takeIf { it.isNotBlank() }

    override suspend fun servers(accountToken: String): List<ServerChoice> {
        val servers = client.get(
            PlexClient.PLEX_TV,
            "/api/v2/resources",
            ListSerializer(PlexResource.serializer()),
            accountToken,
            mapOf("includeHttps" to "1", "includeRelay" to "1")
        ).filter { it.isServer && it.connections.isNotEmpty() }
        servers.forEach { resources[it.clientIdentifier] = it }
        return servers
            .sortedByDescending { it.owned }
            .map { ServerChoice(id = it.clientIdentifier, name = it.name ?: it.clientIdentifier, owned = it.owned) }
    }

    override suspend fun signIn(accountToken: String, server: ServerChoice): ServerSignIn {
        val resource = resources[server.id] ?: run {
            servers(accountToken)
            resources[server.id]
        } ?: throw ServerException(ServerFailure.NOT_FOUND)
        val token = resource.accessToken?.takeIf { it.isNotBlank() } ?: accountToken
        val user = client.get(PlexClient.PLEX_TV, "/api/v2/user", PlexUser.serializer(), accountToken)
        val address = firstReachable(resource, token) ?: throw ServerException(ServerFailure.UNREACHABLE)
        return ServerSignIn(
            address = address,
            serverName = resource.name ?: server.name,
            serverId = resource.clientIdentifier,
            userId = user.uuid ?: user.id?.toString() ?: throw ServerException(ServerFailure.FAILED),
            userName = user.username ?: user.title ?: "Plex",
            token = token
        )
    }

    private suspend fun firstReachable(resource: PlexResource, token: String): String? {
        for (uri in orderedConnections(resource)) {
            try {
                client.execute("GET", uri, "/identity", token, probe = true)
                return uri
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    // ---- Library ----

    override suspend fun libraries(session: ServerSession): List<ServerLibrary> =
        get(session, "/library/sections").directories.mapNotNull { section ->
            val kind = libraryKind(section.type) ?: return@mapNotNull null
            ServerLibrary(id = section.key, name = section.title.orEmpty(), kind = kind)
        }

    override suspend fun libraryPage(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> {
        val container = get(
            session,
            "/library/sections/${pathSegment(library.id)}/all",
            sectionQuery(library.kind) + page(start, limit) + mapOf("sort" to "addedAt:desc")
        )
        return ServerPage(container.metadata.mapNotNull(mapper(session)::title), container.totalSize ?: container.size)
    }

    override suspend fun collectionPage(
        session: ServerSession,
        collectionId: String,
        start: Int,
        limit: Int
    ): ServerPage<ServerTitle> {
        val container = get(
            session,
            "/library/collections/${pathSegment(collectionId)}/children",
            mapOf("includeGuids" to "1") + page(start, limit)
        )
        return ServerPage(container.metadata.mapNotNull(mapper(session)::title), container.totalSize ?: container.size)
    }

    override suspend fun search(
        session: ServerSession,
        kind: ServerMediaKind,
        libraries: List<ServerLibrary>,
        query: String,
        limit: Int
    ): List<ServerTitle> {
        val items = coroutineScope {
            libraries.map { library ->
                async {
                    get(
                        session,
                        "/library/sections/${pathSegment(library.id)}/all",
                        sectionQuery(kind) + page(0, limit) + mapOf("title" to query)
                    ).metadata
                }
            }.awaitAll().flatten()
        }
        return items.mapNotNull(mapper(session)::title).distinctBy { it.preview.id }.take(limit)
    }

    override suspend fun resumeItems(session: ServerSession, limit: Int): List<ServerTitle> {
        val libraries = session.connection.selectedLibraries
        val selectedSections = libraries.map { it.id }.toSet()
        return get(session, "/library/onDeck", mapOf("includeGuids" to "1") + page(0, limit))
            .metadata
            .filter { item -> item.librarySectionID == null || item.librarySectionID.toString() in selectedSections }
            .filter { (it.viewOffset ?: 0L) > 0L }
            .mapNotNull(mapper(session)::resumeTitle)
            .filter { title ->
                val kind = ServerMediaKind.fromContentType(title.preview.apiType)
                kind != null && libraries.any { it.holds(kind) }
            }
            .distinctBy { it.preview.id }
    }

    override suspend fun details(session: ServerSession, itemId: String): ServerItemDetails {
        val item = metadata(session, itemId)
        val episodes = if (item.mediaKind() == ServerMediaKind.SERIES) episodes(session, itemId) else emptyList()
        val mapper = mapper(session)
        return ServerItemDetails(
            meta = mapper.details(item, episodes),
            externalIds = item.externalIds(),
            userStates = (if (episodes.isEmpty()) listOf(item) else episodes).map(mapper::userState)
        )
    }

    override suspend fun candidates(session: ServerSession, itemId: String): List<ServerCandidate> =
        mapper(session).candidates(metadata(session, itemId))

    override suspend fun setPlayed(session: ServerSession, itemId: String, played: Boolean) {
        client.execute(
            "GET",
            session.connection.address,
            if (played) "/:/scrobble" else "/:/unscrobble",
            session.token,
            mapOf("identifier" to LIBRARY_IDENTIFIER, "key" to itemId)
        )
    }

    override suspend fun externalIdIndex(
        session: ServerSession,
        library: ServerLibrary,
        start: Int,
        limit: Int
    ): ServerPage<ServerIndexEntry> {
        val container = get(
            session,
            "/library/sections/${pathSegment(library.id)}/all",
            sectionQuery(library.kind) + page(start, limit)
        )
        return ServerPage(
            items = container.metadata.map { ServerIndexEntry(itemId = it.ratingKey, ids = it.externalIds(), year = it.year) },
            totalCount = container.totalSize ?: container.size
        )
    }

    override suspend fun findEpisode(
        session: ServerSession,
        seriesItemId: String,
        season: Int,
        episode: Int
    ): ServerEpisode? =
        episodes(session, seriesItemId)
            .filter { it.parentIndex == season && it.index == episode }
            .singleOrNull()
            ?.let { ServerEpisode(itemId = it.ratingKey, premiereDate = it.originallyAvailableAt) }

    // ---- Playback ----

    override suspend fun preparePlayback(session: ServerSession, request: ServerPlaybackRequest): ServerPlaybackSession =
        playbackSession(session, request, metadata(session, request.target.item.itemId), UUID.randomUUID().toString())

    internal fun playbackSession(
        session: ServerSession,
        request: ServerPlaybackRequest,
        item: PlexMetadata,
        playSessionId: String
    ): ServerPlaybackSession {
        val medias = item.media.filter { it.parts.isNotEmpty() }
        val mediaIndex = medias.indexOfFirst { it.id.toString() == request.target.mediaSourceId }.takeIf { it >= 0 } ?: 0
        val media = medias.getOrNull(mediaIndex) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val part = media.parts.first()
        val base = session.connection.address
        val identity = client.identityQuery(session.token)
        val (url, method) = if (request.capabilities.allowDirectPlay && part.key != null) {
            buildUrl(base, part.key, identity) to ServerPlayMethod.DIRECT_PLAY
        } else {
            buildUrl(
                base,
                "/video/:/transcode/universal/start.m3u8",
                mapOf(
                    "path" to "/library/metadata/${item.ratingKey}",
                    "mediaIndex" to mediaIndex.toString(),
                    "partIndex" to "0",
                    "protocol" to "hls",
                    "fastSeek" to "1",
                    "directPlay" to "0",
                    "directStream" to "1",
                    "directStreamAudio" to "1",
                    "videoQuality" to "100",
                    "maxVideoBitrate" to MAX_VIDEO_BITRATE_KBPS.toString(),
                    "videoResolution" to "3840x2160",
                    "subtitles" to "burn",
                    "session" to playSessionId,
                    "X-Plex-Session-Identifier" to playSessionId
                ) + identity
            ) to ServerPlayMethod.TRANSCODE
        }
        item.duration?.let { durations[url] = it }
        val subtitles = part.streams
            .filter { it.streamType == PlexStream.SUBTITLE && it.key != null }
            .map { stream ->
                Subtitle(
                    id = "${media.id}:${stream.id}",
                    url = buildUrl(base, stream.key!!, mapOf("X-Plex-Token" to session.token)),
                    lang = stream.languageCode ?: stream.language ?: "und",
                    addonName = stream.displayTitle ?: displayName,
                    addonLogo = null,
                    isStreamProvided = true
                )
            }
        return ServerPlaybackSession(
            target = request.target,
            mediaSourceId = media.id.toString(),
            url = url,
            headers = emptyMap(),
            subtitles = subtitles,
            playSessionId = playSessionId,
            playMethod = method
        )
    }

    override suspend fun report(session: ServerSession, playback: ServerPlaybackSession, event: ServerPlaybackEvent) {
        val itemId = playback.target.item.itemId
        val duration = durations[playback.url]
        val state = when {
            event.type == ServerPlaybackEventType.STOP -> "stopped"
            event.isPaused || event.type == ServerPlaybackEventType.PAUSE -> "paused"
            else -> "playing"
        }
        val position = event.positionMs.coerceAtLeast(0L)
        client.execute(
            "GET",
            session.connection.address,
            "/:/timeline",
            session.token,
            mapOf(
                "ratingKey" to itemId,
                "key" to "/library/metadata/$itemId",
                "state" to state,
                "time" to position.toString(),
                "duration" to duration?.toString(),
                "X-Plex-Session-Identifier" to playback.playSessionId
            )
        )
        if (event.type != ServerPlaybackEventType.STOP) return
        durations.remove(playback.url)
        if (duration != null && duration > 0 && position >= duration * WATCHED_FRACTION) {
            setPlayed(session, itemId, played = true)
        }
        if (playback.playMethod == ServerPlayMethod.TRANSCODE) {
            runCatching {
                client.execute(
                    "GET",
                    session.connection.address,
                    "/video/:/transcode/universal/stop",
                    session.token,
                    mapOf("session" to playback.playSessionId)
                )
            }
        }
    }

    // ---- Helpers ----

    private suspend fun metadata(session: ServerSession, itemId: String): PlexMetadata =
        get(session, "/library/metadata/${pathSegment(itemId)}", mapOf("includeGuids" to "1"))
            .metadata.firstOrNull() ?: throw ServerException(ServerFailure.NOT_FOUND)

    private suspend fun episodes(session: ServerSession, showId: String): List<PlexMetadata> =
        get(session, "/library/metadata/${pathSegment(showId)}/allLeaves", mapOf("includeGuids" to "1")).metadata

    private suspend fun get(session: ServerSession, path: String, query: Map<String, String?> = emptyMap()): PlexContainer =
        client.get(session.connection.address, path, PlexResponse.serializer(), session.token, query).container

    private fun mapper(session: ServerSession) = PlexMapper(session.connection.address, session.token, session.connection.id)

    private fun sectionQuery(kind: ServerMediaKind): Map<String, String?> = mapOf(
        "type" to when (kind) {
            ServerMediaKind.MOVIE -> "1"
            ServerMediaKind.SERIES -> "2"
            else -> null
        },
        "includeGuids" to "1"
    )

    private fun page(start: Int, limit: Int): Map<String, String> = mapOf(
        "X-Plex-Container-Start" to start.toString(),
        "X-Plex-Container-Size" to limit.toString()
    )

    internal companion object {
        const val LINK_URL = "https://plex.tv/link"
        const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"
        const val MAX_VIDEO_BITRATE_KBPS = 40_000
        const val WATCHED_FRACTION = 0.9

        /** Local direct connections first, then remote direct ones, then Plex's relay. */
        fun orderedConnections(resource: PlexResource): List<String> =
            resource.connections
                .filter { it.uri.toHttpUrlOrNull() != null }
                .sortedBy { connection ->
                    when {
                        connection.relay -> 2
                        connection.local -> 0
                        else -> 1
                    }
                }
                .map { it.uri.trimEnd('/') }
                .distinct()
    }
}

private fun String.urlPart(): String = java.net.URLEncoder.encode(this, "UTF-8")
