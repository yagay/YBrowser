# Desktop tab sizing fix — 2026-10-12

Baseline: `f51065331eebccaac21271165e6a00239468229c`.

## Root causes

The desktop tab strip's width/height sliders could only enlarge the default label: minimum width was hardcoded to 96 dp and minimum height to 48 dp, repeated in `BrowserPanels.kt`, `BrowserStore.kt` and `DesktopTabStrip.kt`. Inactive tabs had another 88 dp minimum, compact tabs used a fixed 56 dp width and did not expose a size control. The trailing Material3 IconButtons imposed a 48 dp minimum height even if the slider restrictions were relaxed.

## Changes

- Centralized limits and chip adaptation in `DesktopTabSizing`.
- Normal tab width: **36–320 dp**; compact icon width: **28–160 dp**; tab strip height: **28–88 dp**.
- A compact-mode width slider uses a distinct persisted setting. Existing installations keep their 56 dp compact default, 144 dp desktop default and 48 dp height.
- Apply the same bounds on setting load, save, UI sliders, foreground and background chip layout.
- Narrow chips automatically hide optional labels/close/more/pin controls rather than making the tab wider than requested. Full tab operations remain accessible in the tab switcher.
- Replace the right-hand group's add/expand IconButtons with explicitly-sized actions. Material3 IconButton enforces a minimum 48 dp interactive layout, so it prevented reducing the tab-strip height.
- Unit tests cover smaller widths/heights, foreground/background tabs, compact width and responsive action visibility.

## Validation

Run Android browser and AI workspace unit tests plus ARM64 Debug build. On a phone, test desktop/grouped/compact tabs at minimum and default sizes, horizontal overflow, resizing while tabs are open, saving/reopening settings, and both top and bottom strip positions. The smallest tap targets are intentionally user-adjustable and may be less accessible than the 48 dp touch-target recommendation.
