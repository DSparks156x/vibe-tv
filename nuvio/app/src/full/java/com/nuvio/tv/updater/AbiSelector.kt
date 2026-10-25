package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

/**
 * Vibe fork: releases carry vibe-nuvio-<abi>.apk (armeabi-v7a, arm64-v8a, x86, x86_64, universal)
 * next to the launcher's own APK. Take the device's primary ABI, else the universal one.
 */
internal object AbiSelector {

    private const val ASSET_PREFIX = "vibe-nuvio-"
    private const val UNIVERSAL = "universal"

    fun chooseBestApkAsset(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()
    ): GitHubAssetDto? {
        fun named(abi: String) = assets.firstOrNull { it.name.equals("$ASSET_PREFIX$abi.apk", ignoreCase = true) }
        return supportedAbis.firstOrNull()?.let(::named) ?: named(UNIVERSAL)
    }
}
