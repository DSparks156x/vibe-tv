package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Public Trakt apps have no client secret: it must be left out of the body, not sent as "". */
class TraktAuthDtosTest {
    // Same setup as NetworkModule.provideMoshi (nulls are not serialized).
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun omitsMissingClientSecret() {
        assertEquals(
            """{"code":"c","client_id":"id"}""",
            moshi.adapter(TraktDeviceTokenRequestDto::class.java).toJson(TraktDeviceTokenRequestDto("c", "id"))
        )
        assertEquals(
            """{"token":"t","client_id":"id"}""",
            moshi.adapter(TraktRevokeRequestDto::class.java).toJson(TraktRevokeRequestDto("t", "id"))
        )
        assertEquals(
            """{"refresh_token":"r","client_id":"id","redirect_uri":"u","grant_type":"refresh_token"}""",
            moshi.adapter(TraktRefreshTokenRequestDto::class.java)
                .toJson(TraktRefreshTokenRequestDto(refreshToken = "r", clientId = "id", redirectUri = "u"))
        )
    }

    @Test
    fun keepsClientSecretWhenPresent() {
        assertEquals(
            """{"code":"c","client_id":"id","client_secret":"s"}""",
            moshi.adapter(TraktDeviceTokenRequestDto::class.java).toJson(TraktDeviceTokenRequestDto("c", "id", "s"))
        )
    }
}
