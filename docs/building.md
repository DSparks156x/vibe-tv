# Building

For installing a release, see [install.md](install.md). This page is for building the APKs yourself.

## Requirements
- JDK 17+ (Android Studio's bundled JBR works) and `adb`.
- Android SDK Platform 36 and build-tools 36.1.0. Accept the SDK licenses once so Gradle can download what it needs.
- For Nuvio's Dolby Vision converter: NDK 29.0.14206865 (SDK Manager > "NDK (Side by side)").
  AGP fetches CMake 3.22.1 itself. Skip both with `DOVI_NATIVE_ENABLED=false` in `nuvio/local.properties`;
  profile 7 files then play their HDR10 base layer.
- An Android TV / Google TV device on Android 9 or newer with **Wireless debugging** (or USB debugging) on.

## 1. Signing key (shared by both apps)
The launcher reads Nuvio's data through a **signature**-protected provider, so both APKs must be signed
with the same key.

**Zero config:** with no key configured, both apps sign release builds with the build machine's debug
key. Build both on the same machine and they match. That's enough for a personal install; skip to step 2.

To use your own key instead (e.g. to build on several machines), create a keystore once:
```bash
keytool -genkeypair -keystore vibe.jks -alias vibe -keyalg RSA -keysize 2048 -validity 10000
```

**Launcher.** Write a `signing.properties` file outside the repo:
```properties
storeFile=/absolute/path/to/vibe.jks
storePassword=...
keyAlias=vibe
keyPassword=...
```
Then point `launcher/local.properties` at it:
```properties
sdk.dir=/path/to/Android/Sdk
VIBE_SIGNING_PROPERTIES=/absolute/path/to/signing.properties
```
**Nuvio.** Add the same key to `nuvio/local.properties`:
```properties
NUVIO_RELEASE_STORE_FILE=/absolute/path/to/vibe.jks
NUVIO_RELEASE_STORE_PASSWORD=...
NUVIO_RELEASE_KEY_ALIAS=vibe
NUVIO_RELEASE_KEY_PASSWORD=...
```
Copy the rest of `nuvio/local.example.properties` as needed. Useful ones:
- `VIBE_ABIS=armeabi-v7a`: build only the 32-bit ABI most TVs use. Unset, it builds every ABI plus a
  universal APK.
- Dolby Vision profile 7 → 8.1 conversion is on by default, using the libdovi prebuilts in `nuvio/DV7/`
  (see Requirements).

Never commit `local.properties`, `signing.properties` or keystores. `.gitignore` already excludes them.

## 2. Build
```bash
cd launcher && ./gradlew assembleRelease
```
```bash
cd nuvio && ./gradlew assembleFullRelease
```
Use release builds. Debug builds are far too slow on TV hardware.

## 3. Install
```bash
adb connect <tv-ip>:<port>
```
```bash
adb install -r launcher/app/build/outputs/apk/release/app-release.apk
```
```bash
adb install -r nuvio/app/build/outputs/apk/full/release/<nuvio apk>.apk
```
Compiling ahead of time makes a big difference on slow TV CPUs:
```bash
adb shell cmd package compile -m speed -f com.vibe.tvlauncher
```

## 4. App keys (optional)
Release builds get these from repo secrets in CI (see `.github/workflows/release.yml`). For your own
build, put them in `nuvio/local.properties`: `TMDB_API_KEY`, `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`,
`SIMKL_CLIENT_ID`, `MDBLIST_CLIENT_ID`, `PREMIUMIZE_CLIENT_ID`. Without them, the matching integrations are off.

A default account server can be baked in with `NUVIO_SUPABASE_URL`, `NUVIO_SUPABASE_ANON_KEY` and
`AVATAR_PUBLIC_BASE_URL` (`<url>/storage/v1/object/public/avatars`). Otherwise users pick one in Settings.

Then continue with [install.md](install.md) from step 3.

## Extras: `launcher/tools/`
- `rebrand_tizentube.ps1`: relabels a [TizenTube](https://github.com/reisxd/TizenTubeCobalt) APK as
  "YouTube" and makes it handle YouTube links, so trailers open in it.
- `removed_packages.txt` / `restore_tv.ps1`: an example list of bloat packages (Hisense / Google TV) and a
  script to restore them.
