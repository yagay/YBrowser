# YBrowser dark appearance audit — 2026-10-12

Baseline: `badb2d6909f397d49f5bd491109e959a83dd3b00`.

## Verified causes
- A manual DARK/AMOLED setting only changed Compose's MaterialTheme. Native System WebView looked up `isLightTheme` on its own Android View context, so `prefers-color-scheme` could stay light even when the browser chrome was dark. Enabling algorithmic darkening could therefore appear ineffective.
- GeckoView had no preferred color scheme configured; it continued following device settings rather than explicit browser theme mode.
- `res/values-night/styles.xml` had no `Theme.YBrowser.Popup` override, leaving an inconsistent native light floating window on dark devices.
- `enableEdgeToEdge()` chose status/navigation bar icon contrast when the Activity was created, but the user can change the app's theme without recreation.

## Repairs
1. Share one `BrowserAppearancePolicy` for SYSTEM, LIGHT, DARK and AMOLED, with a separate forced-dark-WebView setting. Unit tests cover Android night-mode overrides and force-dark independence.
2. Create native System WebView using an explicitly light or dark Android `ContextThemeWrapper`; this makes web pages' CSS dark preference independent of the system setting when the user selects LIGHT/DARK/AMOLED. Algorithmic darkening is opt-in and the native color-theme/context is updated in place, without discarding the tab's back stack.
3. Drive GeckoRuntime's `preferredColorScheme` through browser mode: SYSTEM follows Android, LIGHT and DARK override it; AMOLED uses DARK for web content while browser chrome remains OLED black. The algorithmic-darkening option still applies only to System WebView.
4. Sync native status/navigation-bar icon contrast from the browser theme.
5. Add a dark `Theme.YBrowser.Popup` XML style while retaining floating-window flags.
6. Explain the distinction between website-provided dark styling and WebView algorithmic darkening in Settings.

## Acceptance
Run `:ai-workspace:testDebugUnitTest`, `:app:testDebugUnitTest` and ARM64 `assembleDebug` on the final commit. Device checks should cover all four theme modes, light/dark *system* appearance, System WebView CSS media query, a website without dark CSS, GeckoView CSS media query, popup styling and status/navigation bars.

**Limit:** a site may explicitly opt out of algorithmic darkening. The browser cannot guarantee every page will become dark without a custom per-site rewriting engine, which this change intentionally does not add.
