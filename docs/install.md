# Installing

## 1. Get the APKs
From [Releases](../../../releases), download `vibe-launcher.apk` and the Nuvio APK for your TV.
Most TVs want `vibe-nuvio-armeabi-v7a.apk`. If unsure, take `vibe-nuvio-universal.apk`.

## 2. Install them
Either way works:
- **From a computer:** turn on Developer options and **Wireless debugging** (or USB debugging) on the TV, then
  ```bash
  adb connect <tv-ip>:<port>
  ```
  ```bash
  adb install -r vibe-launcher.apk
  ```
  ```bash
  adb install -r vibe-nuvio-armeabi-v7a.apk
  ```
- **On the TV:** install the Downloader app, allow it to install unknown apps, and open the release links in it.

Install both apps from the same release. They must be signed by the same key to talk to each other.

## 3. Make it the home screen
Press Home and pick Vibe, or set it in Settings > Apps > Default apps > Home app.

Some Google TV builds keep forcing their own launcher. On those, disable the stock launcher over adb
(reversible with `pm enable`):
```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```
```bash
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

## 4. Remote buttons
With the stock launcher disabled, Google TV sends the remote's profile and settings (gear) buttons nowhere.
Turn on **Settings > Accessibility > Vibe remote buttons** to make them work again: profile opens the
Vibe profile picker (Nuvio profiles and Vibe settings), the gear opens TV settings. It reads only those
two buttons. Or over adb:
```bash
adb shell settings put secure enabled_accessibility_services com.vibe.tvlauncher/com.vibe.tvlauncher.keys.RemoteKeyService
```
```bash
adb shell settings put secure accessibility_enabled 1
```

## 5. Mic button → Vibe assistant (optional)
Settings > Apps > Default apps > Digital assistant app > **Vibe**, or:
```bash
adb shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.vibe.tvlauncher 0
```
Pick Google again in the same setting to undo.

## 6. Settings
Click your avatar (or press the profile button), then **Vibe settings**. Everything there can also be set from your phone or computer: scan the
QR code on the settings screen (same Wi-Fi) and paste keys and URLs there.
- **Nuvio account:** No account (default), the official Nuvio service, or your own
  [self-hosted server](https://github.com/NuvioMedia/self-host). Picking one opens Nuvio's sign-in.
- **Assistant:** pick Groq or Gemini (both have free API keys) or any OpenAI-compatible server, such as a
  local Ollama. Without one, the assistant still handles plain commands like "play dune".
- **Home rows** and **weather** location and units.
