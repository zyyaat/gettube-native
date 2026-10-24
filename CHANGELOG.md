# Changelog

GetTube release notes — built on the Seal codebase (GPL-3.0).

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
