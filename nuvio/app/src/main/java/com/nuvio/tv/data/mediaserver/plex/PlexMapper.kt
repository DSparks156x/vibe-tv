package com.nuvio.tv.data.mediaserver.plex

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.mediaserver.ServerCandidate
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerTitle
import com.nuvio.tv.data.mediaserver.ServerUserState
import com.nuvio.tv.data.mediaserver.domainType
import com.nuvio.tv.data.mediaserver.mediabrowser.buildUrl
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import kotlin.math.roundToInt

/** Maps Plex metadata onto the same models the Jellyfin/Emby mapper produces. */
internal class PlexMapper(
    private val baseUrl: String,
    private val token: String,
    private val connectionId: String
) {
    fun ref(ratingKey: String): String = ServerItemRef(connectionId, ratingKey).encode()

    fun preview(item: PlexMetadata): MetaPreview? {
        val kind = item.mediaKind() ?: return null
        return MetaPreview(
            id = ref(item.ratingKey),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.title.orEmpty(),
            poster = item.thumb?.let { image(it, height = 600) },
            posterShape = PosterShape.POSTER,
            background = item.art?.let { image(it, width = 1920) },
            logo = null,
            description = item.summary,
            releaseInfo = item.year?.toString(),
            imdbRating = item.displayRating(),
            genres = item.genres.mapNotNull { it.tag },
            released = item.originallyAvailableAt?.take(10)
        )
    }

    fun title(item: PlexMetadata): ServerTitle? = preview(item)?.let { ServerTitle(it, item.externalIds()) }

    /** Continue-watching rows show the series for an episode, like the Jellyfin/Emby mapper. */
    fun resumeTitle(item: PlexMetadata): ServerTitle? {
        if (!item.type.equals("episode", ignoreCase = true)) return title(item)
        val seriesKey = item.grandparentRatingKey ?: return null
        return ServerTitle(
            MetaPreview(
                id = ref(seriesKey),
                type = ContentType.SERIES,
                name = item.grandparentTitle ?: item.title.orEmpty(),
                poster = item.grandparentThumb?.let { image(it, height = 600) },
                posterShape = PosterShape.POSTER,
                background = (item.grandparentArt ?: item.art)?.let { image(it, width = 1920) },
                logo = null,
                description = item.summary,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            ),
            TrackingExternalIds()
        )
    }

    fun details(item: PlexMetadata, episodes: List<PlexMetadata>): Meta {
        val kind = item.mediaKind() ?: ServerMediaKind.MOVIE
        val cast = item.roles.filter { !it.tag.isNullOrBlank() }
        return Meta(
            id = ref(item.ratingKey),
            type = kind.domainType(),
            rawType = kind.contentType,
            name = item.title.orEmpty(),
            poster = item.thumb?.let { image(it, height = 600) },
            posterShape = PosterShape.POSTER,
            background = item.art?.let { image(it, width = 1920) },
            logo = null,
            description = item.summary,
            releaseInfo = item.year?.toString(),
            status = null,
            imdbRating = item.displayRating(),
            genres = item.genres.mapNotNull { it.tag },
            runtime = item.duration?.takeIf { kind == ServerMediaKind.MOVIE }?.let { "${it / 60_000L} min" },
            director = item.directors.mapNotNull { it.tag },
            writer = item.writers.mapNotNull { it.tag },
            cast = cast.mapNotNull { it.tag },
            castMembers = cast.map { role ->
                MetaCastMember(name = role.tag.orEmpty(), character = role.role, photo = role.thumb)
            },
            videos = episodes.map(::video),
            ageRating = item.contentRating,
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = item.externalIds().imdb,
            released = item.originallyAvailableAt?.take(10)
        )
    }

    fun video(item: PlexMetadata): Video = Video(
        id = ref(item.ratingKey),
        title = item.title.orEmpty(),
        released = item.originallyAvailableAt,
        thumbnail = item.thumb?.let { image(it, width = 640) },
        season = item.parentIndex,
        episode = item.index,
        overview = item.summary,
        runtime = item.duration?.let { (it / 60_000L).toInt() },
        rating = item.audienceRating ?: item.rating,
        available = item.media.isNotEmpty()
    )

    fun userState(item: PlexMetadata): ServerUserState = ServerUserState(
        videoId = ref(item.ratingKey),
        positionMs = item.viewOffset ?: 0L,
        durationMs = item.duration ?: 0L,
        played = (item.viewCount ?: 0) > 0,
        lastPlayedEpochMs = item.lastViewedAt?.let { it * 1000L },
        season = item.parentIndex,
        episode = item.index,
        title = item.title
    )

    fun candidates(item: PlexMetadata): List<ServerCandidate> {
        val itemRef = ServerItemRef(connectionId, item.ratingKey)
        return item.media.filter { it.parts.isNotEmpty() }.map { media ->
            val part = media.parts.first()
            val video = part.streams.firstOrNull { it.streamType == PlexStream.VIDEO }
            val audio = part.streams.firstOrNull { it.streamType == PlexStream.AUDIO && it.selected }
                ?: part.streams.firstOrNull { it.streamType == PlexStream.AUDIO }
            ServerCandidate(
                target = ServerPlaybackTarget(item = itemRef, mediaSourceId = media.id.toString()),
                title = resolutionLabel(media) ?: media.container?.uppercase().orEmpty(),
                description = listOfNotNull(
                    video?.displayTitle,
                    audio?.displayTitle,
                    media.container?.uppercase()
                ).joinToString(" • ").ifBlank { null },
                filename = part.file?.substringAfterLast('/')?.substringAfterLast('\\'),
                sizeBytes = part.size
            )
        }
    }

    /** Server-relative image path (e.g. "/library/metadata/1/thumb/2") resized by Plex's photo transcoder. */
    fun image(path: String, width: Int? = null, height: Int? = null): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return buildUrl(
            baseUrl,
            "/photo/:/transcode",
            mapOf(
                "url" to path,
                "width" to (width ?: height?.times(2)?.div(3))?.toString(),
                "height" to (height ?: width?.times(9)?.div(16))?.toString(),
                "minSize" to "1",
                "upscale" to "1",
                "X-Plex-Token" to token
            )
        )
    }

    private fun PlexMetadata.displayRating(): Float? =
        (audienceRating ?: rating)?.let { ((it * 10).roundToInt() / 10.0).toFloat() }
}

internal fun PlexMetadata.mediaKind(): ServerMediaKind? = when (type?.lowercase()) {
    "movie" -> ServerMediaKind.MOVIE
    "show" -> ServerMediaKind.SERIES
    "collection" -> ServerMediaKind.COLLECTION
    else -> null
}

/** Guid entries look like "imdb://tt0133093", "tmdb://603", "tvdb://12345". */
internal fun PlexMetadata.externalIds(): TrackingExternalIds {
    val ids = guids.fold(TrackingExternalIds()) { ids, guid ->
        val namespace = guid.id.substringBefore("://", "").lowercase()
        val value = guid.id.substringAfter("://", "").trim()
        if (namespace.isEmpty() || value.isEmpty()) ids else ids.mergeMissing(parseTrackingExternalIds("$namespace:$value"))
    }
    return ids.copy(imdb = ids.imdb?.takeIf { it.startsWith("tt") })
}

internal fun libraryKind(sectionType: String?): ServerMediaKind? = when (sectionType?.lowercase()) {
    "movie" -> ServerMediaKind.MOVIE
    "show" -> ServerMediaKind.SERIES
    else -> null
}

private fun resolutionLabel(media: PlexMedia): String? = when (val resolution = media.videoResolution?.lowercase()) {
    null, "" -> null
    "4k" -> "4K"
    "sd" -> "SD"
    else -> if (resolution.all(Char::isDigit)) "${resolution}p" else resolution.uppercase()
}
