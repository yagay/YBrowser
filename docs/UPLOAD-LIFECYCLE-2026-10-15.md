# Unified upload lifecycle — 2026-10-15

## Why
The popup WebView lacked onShowFileChooser; regular and popup file input actions must share the same WebChromeClient file prompt bridge. Previously, the single Compose pendingFilePrompt had no tab/document identity and Android picker failures were not handled consistently. Diagnostics contained raw content URIs. Gecko file staging applied a 1 GiB cap per chooser, not across a live session.

## What changed
- System WebView's main and popup clients call one dispatchWebViewFileChooser implementation. Both honor FileChooserParams MIME/multiple/capture flags and Android result parsing. Each ValueCallback finishes at most once and is cancelled if its owning WebView is destroyed.
- PendingBrowserFilePrompt stores originating tab and document URL; BrowserFileChooserPolicy rejects a result after tab closure/switch or a new navigation. Fragment navigation is allowed. Busy pickers reject new prompts rather than risking late Android results reaching new requests.
- All picker launchers handle launch errors by completing the originating prompt. One finishFilePrompt path handles every ActivityResult. Logging exposes only status codes and counts.
- GeckoCoreUploadStager remains asynchronous and owned by each session; it now counts all previously staged files against the session's byte limit. The FileProvider capture helper no longer treats generic */* as an image-capture request.

## Validation
CI covers browser/AI unit tests and ARM64 debug APK. Real-device checks still required for main-page forms, popup forms, upload PDF/ZIP, multi-select, photo/video capture, selection cancellation, navigation/close during chooser, and repeated large files. An HTML chooser success does not guarantee server-side network uploads succeed.
