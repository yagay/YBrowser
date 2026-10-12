# Retained native tab Surface visibility — 2026-10-12

## Reproduction and verified source cause

The browser defaults to `keepTabsActive = true` and up to 12 retained tabs. In this mode, `BrowserApp` mounted all resident WebView/GeckoView instances inside the same full-screen Compose Box. Inactive native views were only hidden by `Modifier.alpha(0f)`, and `Modifier.zIndex` was used to place the selected tab above them.

GeckoView normally draws through an Android native `SurfaceView`. The Surface can compose outside the ordinary Compose alpha drawing layer, allowing one tab's native output to cover a different selected tab. Users see the same webpage even when the selected tab ID and the actual backing native sessions differ.

## Correction

- Give each retained native Android View explicit `View.VISIBLE`/`View.INVISIBLE` visibility based on the selected tab, rather than relying on Compose alpha for a native Surface.
- Apply the visibility both in `AndroidView.factory` and `AndroidView.update` for initial mounting, regular switching and reattachment after Activity recreation.
- Clear focus and deny touch/accessibility for the inactive View; keep the renderer instance alive rather than navigate/reload/replace it on every tab switch.
- Keep native-home overlay as a separate state: when the native new-tab page is visible, no tab's web surface is visible.
- Add independent `BrowserTabSurfacePolicyTest` for multiple tab IDs, switching and native home visibility. No engine/session ID sharing is introduced.

## Validation

GitHub Actions must pass AI workspace tests, browser unit tests and ARM64 Debug APK build. On an Android phone, test 3 distinct sites in GeckoView, WebView, mixed engines, tabs restored from disk, native homepage, and keep-tabs-active on/off. Check background music/playback separately: `View.INVISIBLE` keeps a session but a native renderer may throttle hidden-view rendering; device testing is required before claiming playback is unaffected.
