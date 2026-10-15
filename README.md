# YBrowser V2 — independent Android browser rewrite

This is a clean rewrite on branch `browser-v2-rebuild`; the legacy `main` branch is unchanged.

## Initial milestone
- Material 3 native Android browser with separate WebView per tab.
- Address bar/search, back, forward, refresh, new/close tabs, popup windows.
- Android file picker for single/multiple document uploads; system DownloadManager for HTTP(S).
- Per-site camera and microphone permission prompt, Android runtime permissions.
- Basic tab URL session restoration, OS autofill, safe browsing.
- ARM64-only debug CI and URL normalization unit tests.

**Not yet implemented:** GeckoView, extensions, folder uploads, full incognito isolation, blob downloads, advanced download manager, bookmark/history databases, DuckDuckGo tracker-blocking source, dark-mode per site, AIHub.

## Modules
- `browser-core`: engine-neutral URL address normalizer
- `engine-webview`: Android WebView adapter and callbacks
- `app`: Compose UI, tab management, file picker and permissions

The engine boundary allows future GeckoView support. All code in this initial commit is written from scratch; no DuckDuckGo source is copied. License notices must be reviewed before future upstream code incorporation.

## Installation
The package name is `com.yagay.ybrowser.v2` so you can test V2 alongside the existing YBrowser, with no automatic user data migration.

## Build
JDK 17, Android SDK 37, Gradle 9.6:
```sh
gradle --no-daemon :browser-core:testDebugUnitTest :app:assembleDebug
```
