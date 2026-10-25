# Agent guide: setting up Vibe TV for someone

You are an AI agent helping a person install and configure Vibe TV (a Google TV launcher plus a Nuvio fork).
Read this whole file before acting. Human-facing docs: [install.md](install.md) (install steps) and
[setups.md](setups.md) (what the options mean).

## Ground rules

- **Ask before anything hard to undo**: disabling the stock launcher, changing system settings, installing
  APKs. Never uninstall apps. Never factory-reset or clear app data.
- **Never handle secrets yourself.** API keys, debrid keys, passwords and Plex/Trakt sign-ins are entered by the
  person: on the phone setup page, on the TV, or in the service's own website. You may point to where they go.
  Public client IDs are not secrets, but the person's personal keys are.
- **Check the TV's foreground before sending any key press**, and stop if it isn't the app you expect. The person
  may be watching something:
  `adb shell dumpsys activity activities | grep topResumedActivity`
- Use release APKs only. Debug builds are far too slow on TV hardware.
- Prefer showing the person what to press over driving the TV yourself.

## 1. Ask first (one short round of questions)

1. **TV:** a Google TV / Android TV device on Android 9+? Do they have a computer with `adb` on the same network,
   or will they install from the TV (Downloader app)?
2. **Sources, i.e. where video should come from** (more than one is fine):
   - Stremio add-ons? Which ones, and do they have a debrid service (Real-Debrid, TorBox, Premiumize, AllDebrid)?
   - A Plex, Jellyfin or Emby server? Is it theirs or a friend's (shared)?
   - Neither yet? Then explain the options in [setups.md](setups.md) and let them choose.
3. **Requests:** do they run Overseerr or Jellyseerr? (Usually goes with Plex or Jellyfin.)
4. **Sync:** no account, an official Nuvio account, or their own self-hosted Nuvio server?
5. **Tracking:** Trakt, Simkl, or neither? MDBList for ratings?
6. **Assistant:** do they want the voice assistant with a model? Free options are Groq and Gemini, or a local
   Ollama/LM Studio.
7. **Remote buttons:** OK to make Vibe the home screen, and to turn on its accessibility service so the
   remote's profile and gear buttons work?

## 2. Install

Follow [install.md](install.md):
1. Download `vibe-launcher.apk` and the Nuvio APK for the CPU from the latest GitHub release. Get the CPU with
   `adb shell getprop ro.product.cpu.abi`; `armeabi-v7a` is most common, and `universal` works everywhere.
2. Install both with `adb install -r`. **Both must come from the same release**, because they share a signing key
   and talk to each other.
3. Make Vibe the home app. On Google TV builds that keep forcing the stock launcher, disabling it
   (`pm disable-user`) is reversible, but ask first.
4. Turn on the remote-buttons accessibility service (after asking).
5. Optionally make Vibe the assistant (mic button), per install.md.

## 3. Configure, in this order

1. **Account** (Vibe settings › Nuvio · Account): pick no account, official, or own server. Picking official or
   own server opens Nuvio's sign-in QR; the person scans it.
2. **Sources:**
   - Add-ons: in Nuvio › Settings › Addons, use *Manage from phone* (QR). The person pastes the add-on URLs
     there. Debrid keys usually go inside the add-on's own configure page before copying its URL.
   - Plex: Nuvio › Settings › Content & Discovery › Media servers › Plex, then the code goes in at plex.tv/link.
     If several servers appear, ask which one.
   - Jellyfin or Emby: same menu, with server URL and credentials entered by the person.
3. **Playback** (Vibe settings › Nuvio · Playback): suggest *Auto-pick = First stream* and
   *Reuse last link = on*. Ask before choosing Regex, and build the pattern with them.
4. **Preloading** (Vibe settings › Home › Get ready while I browse): keep *Streams and links* if they have
   debrid and don't mind the extra lookups. Otherwise choose *Find streams*.
5. **Requests** (Vibe settings › Requests): Overseerr/Jellyseerr URL plus API key, entered by the person.
6. **Tracking** (Nuvio settings): Trakt *or* Simkl sign-in by code, MDBList if wanted.
7. **Assistant** (Vibe settings › Assistant): provider, key (entered by the person), model optional.

Easiest path for typing keys: open Vibe settings on the TV and have the person scan the QR. Every Vibe and
Nuvio setting above (except add-ons and media-server sign-ins) can then be filled from their phone or computer.

## 4. Review: walk through this with the person

- [ ] Home shows rows; Continue watching appears after something has been played in Nuvio.
- [ ] A movie from Continue watching or search starts playing. With auto-pick on, no stream list appears.
- [ ] If Plex/Jellyfin is connected, its titles appear and one plays.
- [ ] If Overseerr is set: hold OK on a title, choose *Request*, and the request appears in Overseerr.
- [ ] Profile button or avatar opens the profile picker, and *Vibe settings* opens.
- [ ] Gear button opens TV settings (needs the accessibility service).
- [ ] Mic: "play <a movie they own or can stream>" works. With a model set, "something like <title>" shows results.
- [ ] Updates: the launcher shows *Update* in the top bar when a newer release exists. That only applies to
      installs from a release; local builds never get update offers.

## Troubleshooting pointers

- **Continue watching is empty, or the launcher can't read Nuvio:** the two APKs were signed with different
  keys. Reinstall both from the same release.
- **Picking an account does nothing:** Nuvio restarts after a server switch. Wait a second, then open Nuvio once.
- **Nothing plays:** no source is set up, or torrent results without debrid while P2P is off. Check the add-ons
  and the debrid key.
- **Requests fail:** the Overseerr URL must be reachable from the TV (LAN IP, not localhost), and the key comes
  from Overseerr › Settings › General.
- **Logs:** `adb logcat -s VibeTV VibeVoice VibeUpdate VibePrefetch StreamSearch`.
