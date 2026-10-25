package com.nuvio.tv.data.mediaserver.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// plex.tv account API (https://plex.tv/api/v2/...): plain JSON.

@Serializable
internal data class PlexPin(
    val id: Long,
    val code: String,
    val authToken: String? = null,
    val expiresAt: String? = null
)

@Serializable
internal data class PlexUser(
    val id: Long? = null,
    val uuid: String? = null,
    val username: String? = null,
    val title: String? = null
)

@Serializable
internal data class PlexResource(
    val name: String? = null,
    val clientIdentifier: String,
    val provides: String? = null,
    val owned: Boolean = false,
    val accessToken: String? = null,
    val connections: List<PlexResourceConnection> = emptyList()
) {
    val isServer: Boolean
        get() = provides?.split(',')?.any { it.trim() == "server" } == true
}

@Serializable
internal data class PlexResourceConnection(
    val uri: String,
    val local: Boolean = false,
    val relay: Boolean = false
)

// Plex Media Server API: everything sits under "MediaContainer".

@Serializable
internal data class PlexResponse(
    @SerialName("MediaContainer") val container: PlexContainer = PlexContainer()
)

@Serializable
internal data class PlexContainer(
    val size: Int? = null,
    val totalSize: Int? = null,
    val machineIdentifier: String? = null,
    val friendlyName: String? = null,
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList()
)

@Serializable
internal data class PlexDirectory(
    val key: String,
    val title: String? = null,
    val type: String? = null
)

@Serializable
internal data class PlexMetadata(
    val ratingKey: String,
    val type: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val year: Int? = null,
    val thumb: String? = null,
    val art: String? = null,
    val rating: Double? = null,
    val audienceRating: Double? = null,
    val contentRating: String? = null,
    val duration: Long? = null,
    val viewOffset: Long? = null,
    val viewCount: Int? = null,
    val lastViewedAt: Long? = null,
    val originallyAvailableAt: String? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    val grandparentRatingKey: String? = null,
    val grandparentTitle: String? = null,
    val grandparentThumb: String? = null,
    val grandparentArt: String? = null,
    val librarySectionID: Long? = null,
    @SerialName("Genre") val genres: List<PlexTag> = emptyList(),
    @SerialName("Director") val directors: List<PlexTag> = emptyList(),
    @SerialName("Writer") val writers: List<PlexTag> = emptyList(),
    @SerialName("Role") val roles: List<PlexTag> = emptyList(),
    @SerialName("Guid") val guids: List<PlexGuid> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList()
)

@Serializable
internal data class PlexTag(
    val tag: String? = null,
    val role: String? = null,
    val thumb: String? = null
)

@Serializable
internal data class PlexGuid(val id: String)

@Serializable
internal data class PlexMedia(
    val id: Long,
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoResolution: String? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList()
)

@Serializable
internal data class PlexPart(
    val id: Long,
    val key: String? = null,
    val file: String? = null,
    val size: Long? = null,
    @SerialName("Stream") val streams: List<PlexStream> = emptyList()
)

@Serializable
internal data class PlexStream(
    val id: Long,
    val streamType: Int,
    val key: String? = null,
    val codec: String? = null,
    val language: String? = null,
    val languageCode: String? = null,
    val displayTitle: String? = null,
    val selected: Boolean = false
) {
    companion object {
        const val VIDEO = 1
        const val AUDIO = 2
        const val SUBTITLE = 3
    }
}
