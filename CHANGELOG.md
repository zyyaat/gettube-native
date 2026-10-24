# Changelog

GetTube release notes — built on the Seal codebase (GPL-3.0).

## [1.4.0] - build-12

### Added — native InnerTube fast path (NewPipeExtractor)

- The quality sheet now fetches video info natively through NewPipeExtractor
  (the InnerTube client library used by the NewPipe app) instead of going
  through a yt-dlp process: direct requests to YouTube's player API, no
  Python process startup, and no QuickJS signature-challenge solving —
  measured end-to-end at ~2-4s with the full quality list (video up to 4K
  plus every audio quality, exact file sizes), versus 15-20s+ for on-device
  yt-dlp extraction
- The fetched info is mapped into a yt-dlp-compatible JSON, so the existing
  download flow reuses it via --load-info-json and starts downloading
  immediately (schema verified against both the engine-bundled yt-dlp
  2025.11.12 and current stable 2026.10.21, including a real merged
  144p+m4a download through the synthesized JSON)
- Bulletproof fallbacks, in order: native InnerTube → pinned JS-less yt-dlp
  clients (visionos/android_vr, build-11) → default yt-dlp clients. Live
  streams, age-gated videos and extractor breakage automatically route to
  yt-dlp, which handles cookies and authentication that the native path
  does not
- NewPipeDownloader implemented on OkHttp with timeouts; ProGuard keep
  rules added for NewPipeExtractor, nanojson and Rhino (release minification)

## [1.3.6] - build-11

### Changed — quality analysis is now fast on the FIRST fetch too

- build-10's cache only helped repeat fetches; the first fetch (and any fetch
  after the player JS changed) still paid the full JS challenge cost. Real
  benchmarking (same yt-dlp versions the app ships/auto-updates to,
  2025.11.12 and 2026.10.21) shows the dominant cost is not the network — it
  is yt-dlp's default client chain: it starts with `tv`/`tv_samsung`, whose
  formats require solving YouTube's obfuscated signature challenges in an
  embedded JavaScript engine (QuickJS on the phone), then falls through more
  clients sequentially
- Info fetches now pin JS-less YouTube clients first:
  `--extractor-args "youtube:player_client=visionos,android_vr"`. visionos is
  the JS-less default of current yt-dlp releases; android_vr plays the same
  role on the engine-bundled 2025.11.12 (unsupported pins are skipped
  silently, so the chain survives yt-dlp upgrades). No player JS download,
  no QuickJS solving — measured at ~2-4s with the full format list (31-49
  formats incl. 4K, every format carrying a working URL)
- The quality-sheet fetch falls back automatically to the default client
  chain if the pinned run fails or returns no formats, so nothing regresses
  if YouTube breaks one of these clients
- Fallback extraction at download time (playlists, retries, stale cached
  JSON) pins the same JS-less clients — playlist entries no longer pay the
  challenge cost per video

## [1.3.5] - build-10

### Changed — much faster repeat info fetching
- yt-dlp re-downloads YouTube's player JavaScript and re-solves its signature
  challenges on every single call unless caching is on — the engine injects
  --no-cache-dir into any request without a cache directory. That re-download
  plus re-solving is the bulk of the "Analyzing available qualities…" wait,
  especially on mobile data
- All engine calls (quality-sheet fetch, downloads, playlist info) now share
  one persistent cache directory: the player JS, the solved signature
  functions and the challenge-solver scripts are fetched once and reused, so
  the info fetch drops from ~15-20s to a few seconds on every run after the
  first one (the first run still pays the one-time download)
- Fallback extraction at download time (playlists, retries, stale cached JSON)
  now skips the same watch-page and client-config round-trips the quality
  sheet already skips

## [1.3.4] - build-9

### Fixed — "No supported JavaScript runtime" breaking video info fetching
- yt-dlp 2025.11+ resolves YouTube's JavaScript challenges through an external
  JS runtime; without one, fetching video info fails with "No supported
  JavaScript runtime could be found. Only deno is enabled by default…" — and
  our engine (youtubedl-android 0.17.3) shipped with no runtime at all, while
  its auto-updater kept pulling yt-dlp nightly builds that demand one
- Upgraded the engine to youtubedl-android 0.18.1: it now bundles the QuickJS
  runtime (libqjs.so, all four ABIs) next to yt-dlp 2025.11.12 and wires them
  together automatically (`--js-runtimes quickjs:…`) on every call — no app
  code changes needed, the API is identical
- The extracted yt-dlp is now reset once per app update to the bundled
  known-good version: the engine never re-extracts while the binary exists, so
  an older extraction would keep rejecting the new runtime flag with
  "no such option" and break every download
- The built-in yt-dlp auto-updater now follows the stable channel instead of
  nightly — nightly builds are exactly what pulled in the JS-runtime
  requirement and broke fetching between app releases

## [1.3.3] - build-8

### Changed — faster info fetching & instant download start
- The info fetch now passes `youtube:player_skip=webpage,configs` to yt-dlp:
  the watch-page HTML and the tv client config are no longer downloaded, so
  "Analyzing available qualities…" finishes noticeably sooner (the player
  response alone carries every field the quality sheet needs)
- When a download starts within 10 minutes of the sheet's fetch, the raw info
  JSON is handed back to yt-dlp via `--load-info-json`: the download engine
  skips its whole extraction phase (webpage, player JS, client APIs, m3u8)
  and starts writing bytes immediately instead of re-analyzing the video
- yt-dlp itself falls back to normal re-extraction from the URL if the cached
  JSON is ever stale, so nothing can break — downloads stay as reliable as
  before, just without the duplicated network work

## [1.3.2] - build-7

### Fixed — video info fetched twice
- Picking a quality then starting the download used to spawn yt-dlp twice for
  the same video: once when the quality sheet analyzed the available formats
  (`yt-dlp -J`) and a second time inside the download engine before the actual
  download started — doubling the wait on slow connections
- A small in-memory cache (24 videos, 10-minute freshness) now reuses the
  sheet's fetch inside the download engine, so downloads start right after the
  quality is confirmed and the queue card no longer lingers on "Fetching info"
- Every other info path (retry, the "all formats" page, pasted links) reuses
  the same cache automatically

## [1.3.1] - build-6

### Removed — legacy "Adjust settings" dialog
- The old Seal configure dialog ("Adjust settings before download" with the
  Audio / Video / Playlist chips and the "Fetching video info" row) no longer
  appears in the download flow at all — it was still shown for the first few
  seconds while the real formats were being fetched, before the quality sheet
  appeared
- The sheet now opens **directly in GetTube style**, exactly like the original
  Flutter app: a clean "Analyzing available qualities…" loading state, then the
  classic quality list
- Fetch failures now show a GetTube-styled error card with a Retry button (and
  a "Download with saved settings" safety hatch) instead of the legacy preset UI
- Opening a playlist link now shows a GetTube playlist card with a
  "Download entire playlist" button instead of the type-selection chips
- The legacy configure UI survives only for the advanced custom-command flow

## [1.3.0] - build-5

### Changed — classic GetTube quality sheet
- The quality selection screen is rebuilt to match the original GetTube
  Flutter app exactly: bold title header with the "N Choose quality • Free ✦
  Zero ads" meta line, then three sections — **High quality (auto muxed)**,
  **Video + audio (instant)** and **Audio only**
- Every row is a bordered card with a circular camera/music icon, the exact
  container + codec ("MP4 (H264)"), an estimated size and a DASH marker for
  merged rows — same look as the old `_OptionTile` cards
- **Select-then-confirm flow**: tapping a row only selects it (violet border
  + check mark); the full-width download button at the bottom stays dimmed
  until a quality is chosen, then reads "Download — 1080p60"
- Each row now maps to a precise yt-dlp format expression (codec-aware:
  avc1/vp09/av01 with height caps) instead of the coarse resolution presets
- The recommended "Best" badge marks the highest H264 1080p row, identical
  to the old app's rule

## [1.2.1] - build-4

### Changed — lighter downloads (per-ABI export)
- Release APKs are now split strictly per architecture: **no more 138 MB
  universal file** — every APK carries only its own device's native
  libraries (~40 MB each)
- The release page now lists one separate file per ABI: `arm64-v8a` (modern
  phones), `armeabi-v7a` (32-bit devices), `x86_64` and `x86` (emulators and
  Intel devices — newly included as separate files)
- Release notes and the README download table updated to guide users to the
  right file for their device

## [1.2.0] - build-3

### Added — "نفس التطبيق القديم" UI identity
- **Bottom navigation bar** (Search / Downloads / Settings) replacing Seal's
  navigation drawer — identical structure to the original GetTube Flutter app
- **GetTube brand colors**: deep-violet seed `#7C4DFF` (the Flutter app's
  `AppColors.seed`) applied to light & dark themes
- **Search tab is now the home screen**: brand header, filled rounded search
  field, recent-search chips (persisted, max 8, clear-all) and the gradient
  hero empty state with feature chips
- Quick-download button on every search result (opens the quality sheet
  directly) and URL paste detection — same behavior as the old app
- Download configuration sheet hoisted to application level: it now opens on
  top of ANY tab (previously forced a jump to the queue tab)

### Changed
- Simplified Settings: appearance, download directory, format, general,
  network, troubleshooting, about — removed sponsor cards and Seal extras
- About page now points to the GetTube repository (Seal attribution kept in
  Credits)
- Queue header shows the GetTube brand icon instead of the drawer menu

## [1.1.0] - build-2

### Added
- In-app YouTube search (`ytsearch20` via yt-dlp, Arabic + English UI)
- Real per-video quality chips: the configure sheet now fetches every actual
  format of the video (video heights, original audio, MP3) before download
- GetTube launcher icon (legacy + adaptive, all densities)

## [1.0.0] - build-1

### Added
- GetTube initial release based on Seal upstream main branch (Sept 2026 state)
- Full yt-dlp engine via youtubedl-android 0.17.3 (Python + FFmpeg + aria2c bundled)
- Rebranded application id `com.gettube.app`, app name GetTube
- Signed release builds published automatically via GitHub Actions on `build-*` tags
