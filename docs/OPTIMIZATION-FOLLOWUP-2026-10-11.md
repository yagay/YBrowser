# YBrowser performance and correctness follow-up — 2026-10-11

Baseline: `18c61bc6120ca74ad6e3e03a5d05feab776d54b7` (GitHub Actions passed).

This batch concentrates on frequently triggered browser paths and ownership errors found during a follow-up review. It does not replace WebView or GeckoView implementations.

## Changes

1. **History on navigation:** `BrowserApp` now serializes history/trail writes and moves JSON parsing and persistence to `Dispatchers.IO`. A running write is allowed to complete when rapid navigation cancels its Compose effect; selected-profile state updates occur on the main thread. The history effect also keys on title, active tab and profile, avoiding outdated title/profile state. `BrowserStore` history methods serialize concurrent accesses.
2. **Filter rule parsing:** `CustomFilterRepository` caches its parsed subscription JSON based on the source value. It invalidates the cache on in-app edits and automatically recognizes data changed by a backup restore. Host aggregation uses a set instead of constructing an intermediate combined list of up to 1.4 million entries.
3. **Tab preview reliability:** `TabPreviewRepository` now takes its own Bitmap copy when `createScaledBitmap` returns the caller's original. Previously, the asynchronous writer could recycle an image still displayed by Compose. Both dimensions are bounded, cached images have decode/buffer limits, and previews are persisted via `AtomicFile`, preserving the previous committed file after an interrupted save.
4. **Navigation trail keys:** `BrowserNavigationTrails` allocates collision-free IDs when multiple transitions occur in one millisecond; record and clear operations are synchronized. The list also uses safe index-disambiguated keys when loading legacy duplicate IDs.
5. **Tests:** pure Kotlin unit tests cover image dimension bounds, overflow handling and collision-free navigation IDs.

## Remaining validation

Run `:ai-workspace:testDebugUnitTest`, `:app:testDebugUnitTest`, and ARM64 `assembleDebug` on the pushed commit. Check preview capture/retention, browser-back/forward and private tabs on device. Android Surface/IME problems still require dedicated real-device testing; this code review does not claim all browser issues are solved.
