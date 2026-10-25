# Picking a setup

Vibe TV is a launcher plus a Nuvio build. What you watch comes from whichever **sources** you connect to Nuvio,
and everything else is optional. Mix and match; most people use one or two rows of the table below.

> Setting this up with an AI agent (Claude Code, etc.)? Point it at [agent-setup.md](agent-setup.md).

## Sources: where the video comes from

| You have… | Connect it in Nuvio | Notes |
|---|---|---|
| **Stremio add-ons** (Torrentio, Comet, MediaFusion, …) | Settings › Addons. *Manage from phone* shows a QR code that opens a page for pasting add-on URLs. | The usual setup. Add-ons find streams; on their own, torrent results need P2P or a debrid service. |
| **A debrid service** (Real-Debrid, TorBox, Premiumize, AllDebrid) | Put your debrid key in the add-on's own configure page (e.g. Torrentio), or in Nuvio's debrid settings for the services it supports directly. | Turns torrent results into fast, cached HTTPS links. This is what makes auto-pick feel instant. |
| **Plex** | Settings › Content & Discovery › Media servers › Plex. Enter the code at plex.tv/link. | Your own library, Plex's On Deck in Continue watching, progress synced back. No debrid needed. |
| **Jellyfin / Emby** | Same menu, *Jellyfin* or *Emby*: server URL plus sign-in. | Same as Plex. |
| **Nothing yet** | – | Browsing works (catalogs, trailers, search), but nothing plays until you add a source. |

## Requests (optional)

| You have… | Set it in Vibe |
|---|---|
| **Overseerr / Jellyseerr** | Profile picker › Vibe settings › Requests: the server URL and API key (Overseerr › Settings › General). Then hold OK on any title and pick *Request*. |

Typical pairings: **Plex + Overseerr** (request it, Radarr/Sonarr fetch it, it shows up in Plex) or
**Jellyfin + Jellyseerr**. Add-ons and media servers can live side by side; Nuvio lists streams from all of them.

## Accounts and sync (optional)

Profile picker › Vibe settings › Nuvio › Account (or Nuvio's own Settings › Account):
- **No account** (default): everything stays on this TV.
- **Official Nuvio**: sign in with a nuvio.tv account (QR). Syncs your library, progress, add-ons and settings.
- **My own server**: a [self-hosted Nuvio backend](https://github.com/NuvioMedia/self-host). Enter its URL, then sign in with the QR.

Watch tracking is separate and also optional: **Trakt** *or* **Simkl** (pick one), plus **MDBList** for ratings
and lists. All three are in Nuvio's settings and use a code you type on your phone.

## Playback (strongly recommended)

Vibe settings › Nuvio · Playback:
- **Auto-pick**: *First stream* (or *Regex*, e.g. `2160p|1080p` and no `CAM`). Left on *Manual*, you get a stream list every time.
- **Reuse last link**: on. Resuming starts instantly with the link you used last time.
- **Next episode auto-play**: on, for binging.

Vibe settings › Home › **Get ready while I browse**: resting on a Continue watching or New episodes card
pre-loads it. *Streams and links* also prepares the debrid link (one debrid lookup per card); use *Find streams*
to skip that. Plex/Jellyfin links are always free to prepare.

## Assistant (optional)

Vibe settings › Assistant. Pick **Groq** or **Gemini** (both have free API keys) or **Custom server** (any
OpenAI-compatible endpoint, e.g. a local Ollama or LM Studio). Without one, the mic still handles plain
commands like "play dune" or "open youtube"; the model adds "something like Interstellar" and the like.

## Everything from your phone

Vibe settings shows a QR code. Scan it on the same Wi-Fi to edit every setting above in a browser, which beats
typing API keys with a remote. The page only works while Vibe settings is open on the TV.
