# Native-first webpage dark mode (2026-10-15)

## Goal
Fix visually invisible icons, labels and gradient surfaces caused by broad CSS recoloring. Preserve the existing global theme and per-site OFF/AUTO/FORCE semantics.

## Engines
- **System WebView:** Use `WebSettingsCompat.setAlgorithmicDarkeningAllowed` when the installed provider reports `ALGORITHMIC_DARKENING`. Do not inject `ybrowser_reader/dark.js` into that WebView; the Chromium algorithm handles the rendered content. OFF explicitly disables algorithmic darkening but does not prevent a website's own `prefers-color-scheme` CSS.
- **Older System WebView:** If native support is missing or configuration throws, use the existing document-start JavaScript with a page-finished fallback. Never run both darkening renderers on the same page.
- **GeckoView:** Continue to propagate the native `preferredColorScheme` and apply the extension fallback only when its light-page detection requires it. Preserves independent site overrides and JS-disabled behavior.
- **Popups:** Use the parent WebView's themed context and native darkening when supported; apply the legacy script only when the provider lacks the algorithm.
- **Per-site FORCE:** A site override can request a dark WebView context independently of the browser chrome's theme.

## Key invariants
1. Native and injected darkening must never be active together in System WebView.
2. OFF must release legacy CSS and native algorithmic darkening, while still respecting a site's native dark CSS.
3. Switching engines must not alter the user's existing per-site saved settings.
4. If JavaScript is disabled, a supported native renderer can still darken webpages.
5. Do not reload tabs or discard navigation history just to change appearance.

## Validation
`app:testDebugUnitTest`, `ai-workspace:testDebugUnitTest`, `node --test tools/browser-dark.test.cjs` and the ARM64 debug APK build. Physical WebView/Gecko rendering should also be checked on search results, SVG icons, photo/canvas surfaces, gradients, log-in popups, forced light browser chrome and JS-disabled pages.
