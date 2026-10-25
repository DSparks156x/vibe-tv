# Setup

## Requirements
- Android SDK (compileSdk 36, build-tools 36.1.0), JDK 17, `adb`.
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
- `VIBE_ABIS=armeabi-v7a`: build only the 32-bit ABI most TVs use (smaller APK, faster build).
- Dolby Vision profile 7 → 8.1 conversion is on by default, using the libdovi prebuilts in `nuvio/DV7/`.
  It needs the Android NDK; AGP installs CMake itself. Set `DOVI_NATIVE_ENABLED=false` to skip it.

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

## 4. Make it the home screen
Press Home and pick Vibe, or set it from Settings > Apps > Default apps > Home app.

Some Google TV builds keep forcing their own launcher. On those, disable the stock launcher. This is
reversible with `pm enable`:
```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```
```bash
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

## 5. Mic button → Vibe assistant (optional)
Settings > Apps > Default apps > Digital assistant app > **Vibe**, or:
```bash
adb shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.vibe.tvlauncher 0
```
To undo, pick Google again in the same setting.

Optionally, set an LLM endpoint for free-form requests: open the overlay, then the gear icon. You can
also set it over adb:
```bash
adb shell am start -n com.vibe.tvlauncher/.voice.VoiceActivity --es provider groq --es apiKey <your key>
```
`provider` is `groq`, `gemini` or `custom` (with `--es baseUrl <url>`, e.g. a local Ollama server).

## 6. Nuvio account / backend
Nuvio works without an account. For sync, either sign in to the public Nuvio service or run your own
backend ([NuvioMedia/self-host](https://github.com/NuvioMedia/self-host)). To connect to your own:
- **No rebuild:** in Nuvio, go to Settings > Account > "Connect to custom server" and enter your server URL.
- **Build default:** set `NUVIO_SUPABASE_URL`, `NUVIO_SUPABASE_ANON_KEY` and `AVATAR_PUBLIC_BASE_URL`
  (`<url>/storage/v1/object/public/avatars`) in `nuvio/local.properties`.

## Extras: `launcher/tools/`
- `rebrand_tizentube.ps1`: relabels a [TizenTube](https://github.com/reisxd/TizenTubeCobalt) APK as
  "YouTube" and makes it handle YouTube links, so trailers open in it.
- `removed_packages.txt` / `restore_tv.ps1`: an example list of bloat packages (Hisense / Google TV) and a
  script to restore them.
