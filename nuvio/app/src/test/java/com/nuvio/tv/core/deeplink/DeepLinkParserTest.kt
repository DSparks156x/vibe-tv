package com.nuvio.tv.core.deeplink

import com.nuvio.tv.domain.deeplink.AppDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkParserTest {
    @Test
    fun parsesMetaQueryDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("nuvio://meta?type=series&id=tt0944947")
        )
    }

    @Test
    fun parsesPartyInviteDeepLink() {
        assertEquals(AppDeepLink.Party("ABC234"), DeepLinkParser.parse("nuvio://party/abc234"))
        assertEquals(AppDeepLink.Party("ABC234"), DeepLinkParser.parse("nuvio://party?code=ABC-234"))
        assertNull(DeepLinkParser.parse("nuvio://party/ABC10"))
    }

    @Test
    fun parsesAccountDeepLink() {
        assertEquals(AppDeepLink.Account, DeepLinkParser.parse("nuvio://account"))
        assertEquals(AppDeepLink.Account, DeepLinkParser.parse("nuvio://account/"))
    }

    @Test
    fun parsesAddonInstallDeepLink() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            DeepLinkParser.parse("nuvio://free.nebulapro.xyz/sports/i/free/manifest.json")
        )
    }

    @Test
    fun parsesStremioAddonInstallDeepLink() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            DeepLinkParser.parse("stremio://free.nebulapro.xyz/sports/i/free/manifest.json")
        )
    }

    @Test
    fun parsesDirectImdbDetailDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("nuvio://series/tt0944947")
        )
    }

    @Test
    fun parsesProviderImdbDetailDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            DeepLinkParser.parse("nuvio://imdb/series/tt0944947")
        )
    }

    @Test
    fun parsesProviderTmdbDetailDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tmdb:1399"),
            DeepLinkParser.parse("nuvio://tmdb/tv/1399")
        )
    }

    @Test
    fun doesNotTreatAuthLinkAsAddonInstall() {
        assertNull(DeepLinkParser.parse("nuvio://auth/trakt?code=abc"))
    }

    @Test
    fun parsesPlayOnLoadDeepLink() {
        assertEquals(
            AppDeepLink.Meta(type = "movie", id = "tt15239678", playOnLoad = true),
            DeepLinkParser.parse("nuvio://detail/movie/tt15239678?playOnLoad=1")
        )
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947", playOnLoad = true, season = 2, episode = 3),
            DeepLinkParser.parse("nuvio://detail/series/tt0944947?playOnLoad=true&season=2&episode=3")
        )
        // A season without an episode is ignored (next unwatched episode is used instead).
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947", playOnLoad = true),
            DeepLinkParser.parse("nuvio://detail/series/tt0944947?playOnLoad=1&season=2")
        )
    }

    @Test
    fun parsesSearchDeepLink() {
        assertEquals(AppDeepLink.Search("the bear"), DeepLinkParser.parse("nuvio://search?q=the%20bear"))
        assertEquals(AppDeepLink.Search(null), DeepLinkParser.parse("nuvio://search"))
    }

    @Test
    fun doesNotTreatNonHostStremioLinkAsAddonInstall() {
        assertNull(DeepLinkParser.parse("stremio://detail/series/tt0944947"))
    }
}
