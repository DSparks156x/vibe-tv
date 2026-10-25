# Vibe TV

An ad-free, Google TV–style home screen for Android TV, built around your own sources instead of
the stock launcher's sponsored rows.

This repository is a monorepo with two Android apps that work together:

| Folder | App | What it is |
|---|---|---|
| [`launcher/`](launcher) | **Vibe launcher** (`com.vibe.tvlauncher`) | The home screen, plus the **Vibe assistant**, which replaces Gemini/Google Assistant on the remote's mic button. |
| [`nuvio/`](nuvio) | **Vibe Nuvio** (`com.vibe.nuvio`) | A fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), the Stremio-addon media player, with launcher integration added. |

## Launcher
- Google TV look: pill tabs (Home / Movies / Shows / Apps / Library), clock and weather, a full-screen
  backdrop that follows focus, and a hero carousel of trending titles.
- Rows: Continue watching and New episodes (from Nuvio), Top picks ranked by your watch history,
  Your apps (editable favorites), "Because you watch …", your Nuvio add-on catalogs, trending,
  trailers, library, top rated.
- Long-press menus: resume, open, trailer, add to or remove from library, mark watched, remove from
  Continue watching. Apps can be moved, added, removed or hidden.
- No ads, no sponsored rows, no account required by the launcher itself.

## Assistant
- Pressing the remote's mic button opens the Vibe overlay instead of Gemini.
- Offline rules handle the common commands: "play Dune", "continue The Bear", "next episode",
  "season 2 episode 3 of …", "X on YouTube", "open Plex", "search …".
- Optional: requests the rules can't handle ("something like Interstellar") go to any
  OpenAI-compatible endpoint with **your own** key. Groq and Gemini free tiers and a local Ollama /
  LM Studio URL are preset. Nothing is sent anywhere unless you configure this.
- Results are always matched to real titles (Cinemeta, your Nuvio catalogs and Continue watching,
  installed apps) before anything plays.

## Nuvio fork
Changes from upstream NuvioTV:
- A signature-protected `ContentProvider` (`com.vibe.nuvio.launcher`) that gives the launcher
  Continue watching, library, profiles and add-ons, plus actions (library toggle, mark watched,
  remove progress, switch profile).
- Deep links: `nuvio://detail/{type}/{id}[?playOnLoad=1&season=N&episode=M]` and `nuvio://search[?q=]`.
- A Google TV–style restyle (the default "Vibe" theme).
- Fixes for self-hosted Nuvio backends (TV QR sign-in through `<server>/tv-login`).
- Playback work (Dolby Vision profile 7 → 8.1, binge flow, subtitle defaults).

The first commit that touches `nuvio/` is an unmodified snapshot of upstream (the commit is named in
its message), so `git diff <that commit> -- nuvio` shows exactly what the fork changes.

## Building and installing
See [docs/setup.md](docs/setup.md).

## License
GPL-3.0 (see [LICENSE](LICENSE)). NuvioTV is GPL-3.0, and the launcher uses the same license.
The bundled Google Sans font is © The Google Sans Project Authors, under the SIL Open Font
License 1.1 ([docs/fonts/GoogleSans-OFL.txt](docs/fonts/GoogleSans-OFL.txt)).

Not affiliated with Google, Stremio or NuvioMedia.
