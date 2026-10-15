# Candy parity: Android webpage file uploads — 2026-10-15

Compared directly with `sk2andy/candy-browser` main: `FileChooserRules`, `BrowserController`, `GeckoFileUploadStager`, `GeckoViewRuntimeHandle`, and `SystemWebViewBrowserEngineAdapter`.

## Structure
- Both WebView and GeckoView emit one `BrowserFilePromptRequest`. System WebView subwindows use the same file chooser dispatch function.
- A pending chooser now records a **tab and native main-document generation**. Engines increment that generation on WebView `onPageStarted` or GeckoView `onPageStart`; an unchanged URL reload invalidates prior requests, while history.pushState route changes don't.
- `BrowserFileChooserPolicy` rejects results if the tab is gone, a different tab is active, an Activity is no longer started, or a new native document loaded. Delivery is once-only using AtomicBoolean.
- `BrowserFileChooserValidator` is run on `Dispatchers.IO`. It enforces structured `content://` URIs, disallows foreign-supplied self-provider URIs (except the exact preallocated capture URI), honors accept types / extensions, verifies actual stream readability, deduplicates and caps to 100 files. Directory selection checks the tree URI but does not try to read it as a regular stream.
- GeckoView gets a real `onFolderUploadPrompt` permission dialog; upload copying is already asynchronous and session-owned, with a cumulative 1 GiB budget and 32 MiB free-space reserve.
- A conservative orphan cleaner removes old (>48h) upload temp session directories not belonging to any live stager. Active uploads are protected.
- Capture output remains available until its tab closes; document URIs are never logged.

## CI and device acceptance
Run `:app:testDebugUnitTest`, `:ai-workspace:testDebugUnitTest`, JS validation and arm64-v8a debug APK build. Real-device acceptance must cover WebView and GeckoView; ordinary image/PDF/ZIP and multi-file inputs; camera; folder upload permission; popup inputs; same-URL reload vs SPA route changes; picker cancellation, tab switches and destroyed sessions; cloud-backed documents and large cache quotas.
