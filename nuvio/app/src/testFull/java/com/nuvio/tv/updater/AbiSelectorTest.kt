package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbiSelectorTest {
    private fun asset(name: String) = GitHubAssetDto(name = name, browserDownloadUrl = "https://example.invalid/$name")

    private val release = listOf(
        asset("vibe-launcher.apk"),
        asset("vibe-nuvio-armeabi-v7a.apk"),
        asset("vibe-nuvio-arm64-v8a.apk"),
        asset("vibe-nuvio-x86.apk"),
        asset("vibe-nuvio-x86_64.apk"),
        asset("vibe-nuvio-universal.apk")
    )

    @Test
    fun picksPrimaryAbi() {
        assertEquals("vibe-nuvio-armeabi-v7a.apk", AbiSelector.chooseBestApkAsset(release, listOf("armeabi-v7a", "armeabi"))?.name)
        assertEquals("vibe-nuvio-x86.apk", AbiSelector.chooseBestApkAsset(release, listOf("x86"))?.name)
    }

    @Test
    fun fallsBackToUniversal() {
        val noArm64 = release.filterNot { it.name.contains("arm64") }
        assertEquals("vibe-nuvio-universal.apk", AbiSelector.chooseBestApkAsset(noArm64, listOf("arm64-v8a", "armeabi-v7a"))?.name)
    }

    @Test
    fun neverPicksTheLauncher() {
        assertNull(AbiSelector.chooseBestApkAsset(listOf(asset("vibe-launcher.apk")), listOf("armeabi-v7a")))
    }
}
