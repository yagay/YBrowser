# YBrowser address bar size controls — 2026-10-12

Baseline: `71311f60464d233335bab7ffe08674efc4212bef`.

## User issue and root cause
The browser exposed adjustable desktop tab width and height but **the address chrome remained fixed** at 48dp for its toolbar row, text field, and five native Material3 IconButtons. As with the old tab bar, adding only a height slider would not work: each Material3 IconButton reserves a minimum interactive layout area, forcing the toolbar back up to at least 48dp.

## Changes
- Introduced a single `BrowserAddressBarSizing` policy with unit tests, including a height-aware cap so child buttons and icons never grow beyond a user-set smaller bar.
- Under **Settings → Appearance**, added independent persisted controls for address bar height (28–88dp), action button size (24–80dp), icon size (12–32dp), input corner radius (0–24dp), and horizontal spacing (0–16dp), plus a one-tap reset to previous defaults.
- Use the same sizing policy on `BrowserStore` load/save and Compose rendering; existing installs receive the defaults **48/48/20/8/2dp**.
- Replaced fixed-size Material3 buttons inside `BrowserChrome` with explicit-size clickable icon containers. This prevents minimum touch-target padding from overriding the chosen smaller height.
- Updated back/forward/reload/tabs/menu/close buttons, input field height, field corner radius, secure-site icon, and row spacing. BrowserApp passes existing `settings`; no engine/session reinitialization is necessary.
- Kept accessibility labels and enabled/disabled behavior on the actions.

## Validation and notes
The requested small buttons can intentionally be below Android's usual 48dp recommended touch target; the default remains 48dp. Test on-device across top/bottom toolbar positions, narrow portrait screens, text input/keyboard, WebView/GeckoView, popup windows and rapid changes to the size sliders.

Verify CI on the exact commit: `:ai-workspace:testDebugUnitTest`, `:app:testDebugUnitTest`, and ARM64 `assembleDebug`. Successful CI alone cannot prove the native UI geometry on the user's device.
