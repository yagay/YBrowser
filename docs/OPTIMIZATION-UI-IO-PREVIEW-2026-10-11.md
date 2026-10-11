# YBrowser browser UI responsiveness and preview optimization — 2026-10-11

Baseline: `46010b88dbdcc48b8f2a55d19fc7b949704ba64d` (CI browser/AI tests and ARM64 Debug passed).

## Verified code paths and improvements

1. **Bookmarks:** Toggling/removing a bookmark previously called `store.saveBookmarks` on the Compose UI thread. Now each click updates in-memory UI state immediately and queues an immutable bookmark snapshot on a shared `Mutex`/IO lane. Bookmark HTML imports use the same mutex to avoid racing with toggles.
2. **History sheet:** Opening history from the address-command and browser toolbar no longer directly parses stored history JSON on the main thread. Loading uses `Dispatchers.IO` and the existing history writer mutex, with profile and sheet-visibility checks before applying results.
3. **Download Manager polling:** While the Downloads sheet is visible, a query previously ran on the UI thread every 700 ms. Queries and post-action refreshes now run on IO and use a generation number so older queries cannot overwrite a more recent refresh. Pause, resume, retry and deletion operations are dispatched off-main as well.
4. **WebView preview capture:** Previous code allocated and drew an ARGB bitmap at full WebView dimensions, then rescaled it to a tab preview. Captures now allocate only a 420 × 720-bounded thumbnail, use canvas scaling, and recycle on draw errors.
5. **One preview dimension policy:** `TabPreviewSizePolicy.fit` now accepts optional maximum dimensions; `TabSessionManager` and native WebView captures use the same policy rather than two slightly different hand-written scale calculations. Persistence continues using the default 480 × 720 bounds.
6. **Regression tests:** Added `TabPreviewSizePolicyTest` cases for alternate viewport limits, aspect ratios and invalid maximum dimensions.

## Validation and remaining acceptance

Run `:ai-workspace:testDebugUnitTest`, `:app:testDebugUnitTest`, and ARM64 `assembleDebug` for this exact commit. Automated compilation is not a replacement for real-device checks of GeckoView Surface focus, WebView screenshot completeness, rapid bookmarking, download pause/resume, and keyboard/system-insets behavior.

## Still worth optimizing

`BrowserApp.kt` has other synchronous settings/profile operations. A larger extraction should be guided by integration tests, particularly Profile switching and restore, rather than an unverified wholesale rewrite.
