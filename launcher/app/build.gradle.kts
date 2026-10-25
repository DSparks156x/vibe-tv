import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: point VIBE_SIGNING_PROPERTIES (local.properties or env) at a properties file with
// storeFile/storePassword/keyAlias/keyPassword. Without it, release builds use the debug key.
// The Nuvio fork must be signed with the same key: its launcher-data provider is signature-protected.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signingProps = Properties().apply {
    val path = localProps.getProperty("VIBE_SIGNING_PROPERTIES") ?: System.getenv("VIBE_SIGNING_PROPERTIES")
    val f = path?.let { file(it) }
    if (f != null && f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = signingProps.getProperty("storeFile") != null

android {
    namespace = "com.vibe.tvlauncher"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.vibe.tvlauncher"
        minSdk = 28
        targetSdk = 34
        // Minutes since 2026-01-01, so every build (local or CI) installs over the previous one.
        versionCode = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()
        versionName = System.getenv("VIBE_VERSION_NAME") ?: "dev"
    }

    signingConfigs {
        create("vibe") {
            storeFile = signingProps.getProperty("storeFile")?.let { file(it) }
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "vibe" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    testImplementation("junit:junit:4.13.2")
    // Real org.json: the Android stub on the unit-test classpath throws.
    testImplementation("org.json:json:20250517")
}
