package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.domain.model.ContentType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing and mapping against Plex's JSON shapes (no live server). */
class PlexProviderTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val provider = PlexProvider(OkHttpClient(), ServerClientIdentity("Test TV", "v0.1.0") { "client-1" })
    private val base = "http://192.168.1.5:32400"
    private val mapper = PlexMapper(base, "tok", "c1")

    private val sections = """
        {"MediaContainer":{"size":3,"Directory":[
          {"key":"1","title":"Movies","type":"movie"},
          {"key":"2","title":"TV Shows","type":"show"},
          {"key":"3","title":"Music","type":"artist"}]}}
    """.trimIndent()

    private val movie = """
        {"MediaContainer":{"size":1,"Metadata":[{
          "ratingKey":"101","type":"movie","title":"The Matrix","summary":"Neo.","year":1999,
          "thumb":"/library/metadata/101/thumb/1700000000","art":"/library/metadata/101/art/1700000000",
          "audienceRating":8.73,"contentRating":"R","duration":8160000,"viewOffset":600000,"viewCount":1,
          "lastViewedAt":1700000000,"originallyAvailableAt":"1999-03-31",
          "Genre":[{"tag":"Action"},{"tag":"Sci-Fi"}],"Director":[{"tag":"Lana Wachowski"}],
          "Role":[{"tag":"Keanu Reeves","role":"Neo","thumb":"https://metadata-static.plex.tv/k.jpg"}],
          "Guid":[{"id":"imdb://tt0133093"},{"id":"tmdb://603"},{"id":"tvdb://169"}],
          "Media":[{"id":5001,"duration":8160000,"videoResolution":"1080","container":"mkv","videoCodec":"h264",
            "Part":[{"id":7001,"key":"/library/parts/7001/1700000000/file.mkv","file":"/media/movies/The Matrix (1999).mkv","size":12345,
              "Stream":[{"id":1,"streamType":1,"displayTitle":"1080p (H.264)"},
                        {"id":2,"streamType":2,"displayTitle":"English (AC3 5.1)","selected":true},
                        {"id":3,"streamType":3,"key":"/library/streams/3","languageCode":"eng","displayTitle":"English (SRT External)"},
                        {"id":4,"streamType":3,"languageCode":"fre","displayTitle":"French (PGS)"}]}]},
            {"id":5002,"videoResolution":"4k","container":"mkv","Part":[{"id":7002,"key":"/library/parts/7002/1/file.mkv"}]}]
        }]}}
    """.trimIndent()

    private val episodes = """
        {"MediaContainer":{"size":2,"Metadata":[
          {"ratingKey":"301","type":"episode","title":"Pilot","index":1,"parentIndex":1,"duration":1320000,
           "thumb":"/library/metadata/301/thumb/1","originallyAvailableAt":"2013-12-02","viewCount":1,
           "Media":[{"id":1,"Part":[{"id":2,"key":"/library/parts/2/1/file.mkv"}]}]},
          {"ratingKey":"302","type":"episode","title":"Lawnmower Dog","index":2,"parentIndex":1,"duration":1320000,
           "viewOffset":60000,"Media":[]}]}}
    """.trimIndent()

    private val resources = """
        [{"name":"Buddy's Plex","clientIdentifier":"srv1","provides":"server","owned":false,"accessToken":"server-tok",
          "connections":[
            {"uri":"https://1-2-3-4.abc.plex.direct:32400","local":false,"relay":false},
            {"uri":"https://5-6-7-8.abc.plex.direct:8443","local":false,"relay":true},
            {"uri":"https://192-168-1-5.abc.plex.direct:32400","local":true,"relay":false}]},
         {"name":"My Phone","clientIdentifier":"p1","provides":"client,player","connections":[]}]
    """.trimIndent()

    private fun container(text: String) = json.decodeFromString(PlexResponse.serializer(), text).container

    @Test
    fun mapsMovieAndShowSections() {
        val libraries = container(sections).directories.mapNotNull { section ->
            libraryKind(section.type)?.let { section.key to it }
        }
        assertEquals(listOf("1" to ServerMediaKind.MOVIE, "2" to ServerMediaKind.SERIES), libraries)
    }

    @Test
    fun mapsMovieMetadata() {
        val item = container(movie).metadata.single()
        val title = mapper.title(item)!!
        assertEquals(ServerItemRef("c1", "101").encode(), title.preview.id)
        assertEquals(ContentType.MOVIE, title.preview.type)
        assertEquals("1999", title.preview.releaseInfo)
        assertEquals(8.7f, title.preview.imdbRating)
        assertEquals(listOf("Action", "Sci-Fi"), title.preview.genres)
        assertEquals("tt0133093", title.externalIds.imdb)
        assertEquals("603", title.externalIds.tmdb?.toString())

        val poster = title.preview.poster!!.toHttpUrl()
        assertEquals("/photo/:/transcode", poster.encodedPath)
        assertEquals("/library/metadata/101/thumb/1700000000", poster.queryParameter("url"))
        assertEquals("tok", poster.queryParameter("X-Plex-Token"))

        val meta = mapper.details(item, emptyList())
        assertEquals("136 min", meta.runtime)
        assertEquals(listOf("Keanu Reeves"), meta.cast)
        assertEquals("tt0133093", meta.imdbId)

        val state = mapper.userState(item)
        assertEquals(600_000L, state.positionMs)
        assertEquals(8_160_000L, state.durationMs)
        assertTrue(state.played)
        assertEquals(1_700_000_000_000L, state.lastPlayedEpochMs)
    }

    @Test
    fun mapsVersionsAsCandidates() {
        val candidates = mapper.candidates(container(movie).metadata.single())
        assertEquals(listOf("5001", "5002"), candidates.map { it.target.mediaSourceId })
        assertEquals(listOf("1080p", "4K"), candidates.map { it.title })
        assertEquals("The Matrix (1999).mkv", candidates.first().filename)
        assertEquals("1080p (H.264) • English (AC3 5.1) • MKV", candidates.first().description)
    }

    @Test
    fun mapsEpisodes() {
        val list = container(episodes).metadata
        val meta = mapper.details(
            json.decodeFromString(PlexMetadata.serializer(), """{"ratingKey":"300","type":"show","title":"Rick and Morty","year":2013}"""),
            list
        )
        assertEquals(ContentType.SERIES, meta.type)
        assertEquals(listOf(1 to 1, 1 to 2), meta.videos.map { it.season to it.episode })
        assertEquals(listOf(true, false), meta.videos.map { it.available })
        assertEquals(listOf(true, false), list.map { mapper.userState(it).played })
    }

    @Test
    fun episodeResumesAsItsSeries() {
        val episode = json.decodeFromString(
            PlexMetadata.serializer(),
            """{"ratingKey":"302","type":"episode","title":"Lawnmower Dog","grandparentRatingKey":"300",
               "grandparentTitle":"Rick and Morty","grandparentThumb":"/library/metadata/300/thumb/1","viewOffset":60000}"""
        )
        val title = mapper.resumeTitle(episode)!!
        assertEquals(ServerItemRef("c1", "300").encode(), title.preview.id)
        assertEquals("Rick and Morty", title.preview.name)
        assertEquals(ContentType.SERIES, title.preview.type)
    }

    @Test
    fun ordersConnectionsLocalThenRemoteThenRelay() {
        val list = json.decodeFromString(ListSerializer(PlexResource.serializer()), resources)
        assertEquals(listOf(true, false), list.map { it.isServer })
        assertEquals(
            listOf(
                "https://192-168-1-5.abc.plex.direct:32400",
                "https://1-2-3-4.abc.plex.direct:32400",
                "https://5-6-7-8.abc.plex.direct:8443"
            ),
            PlexProvider.orderedConnections(list.first())
        )
    }

    @Test
    fun parsesPin() {
        val pin = json.decodeFromString(PlexPin.serializer(), """{"id":42,"code":"ABCD","authToken":null,"expiresAt":"2026-10-25T12:00:00Z"}""")
        assertEquals("ABCD", pin.code)
        assertNull(pin.authToken)
        val approve = provider.approveUrl("ABCD")
        assertTrue(approve.startsWith("https://app.plex.tv/auth#?clientID=client-1&code=ABCD"))
    }

    @Test
    fun directPlayThenTranscodeFallback() {
        val item = container(movie).metadata.single()
        val session = ServerSession(
            ServerConnection(
                id = "c1", providerId = "plex", name = "Buddy's Plex", address = base,
                remoteServerId = "srv1", remoteUserId = "u1", userName = "buddy", credentialRef = "k1"
            ),
            token = "tok"
        )
        val target = ServerPlaybackTarget(ServerItemRef("c1", "101"), mediaSourceId = "5002")

        val direct = provider.playbackSession(session, ServerPlaybackRequest(target, ServerPlayerCapabilities()), item, "s1")
        assertEquals(ServerPlayMethod.DIRECT_PLAY, direct.playMethod)
        assertEquals("5002", direct.mediaSourceId)
        val directUrl = direct.url.toHttpUrl()
        assertEquals("/library/parts/7002/1/file.mkv", directUrl.encodedPath)
        assertEquals("tok", directUrl.queryParameter("X-Plex-Token"))
        assertEquals("client-1", directUrl.queryParameter("X-Plex-Client-Identifier"))

        val first = provider.playbackSession(
            session,
            ServerPlaybackRequest(ServerPlaybackTarget(target.item, "5001"), ServerPlayerCapabilities()),
            item,
            "s2"
        )
        assertEquals(listOf("eng"), first.subtitles.map { it.lang })
        assertEquals("/library/streams/3", first.subtitles.single().url.toHttpUrl().encodedPath)

        val transcode = provider.playbackSession(
            session,
            ServerPlaybackRequest(target, ServerPlayerCapabilities(allowDirectPlay = false)),
            item,
            "s3"
        )
        assertEquals(ServerPlayMethod.TRANSCODE, transcode.playMethod)
        val transcodeUrl = transcode.url.toHttpUrl()
        assertEquals("/video/:/transcode/universal/start.m3u8", transcodeUrl.encodedPath)
        assertEquals("/library/metadata/101", transcodeUrl.queryParameter("path"))
        assertEquals("1", transcodeUrl.queryParameter("mediaIndex"))
        assertEquals("s3", transcodeUrl.queryParameter("session"))
        assertFalse(transcode.url.contains("allowDirectPlay"))
    }
}
