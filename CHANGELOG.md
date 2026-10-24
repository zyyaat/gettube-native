# Changelog

GetTube release notes — built on the Seal codebase (GPL-3.0).

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
