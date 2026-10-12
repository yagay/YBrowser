# Native Autofill functional diagnosis and virtual-field fix — 2026-10-12

Base: `86209840c79300296e890251d802d3e0af036208`.

## Confirmed cause

The first release implemented a menu action by calling `AutofillManager.requestAutofill(view)` against the top-level WebView/GeckoView. HTML form fields are Android **virtual children**. GeckoView exposes the native virtual field through `session.autofillSession.focusedData.id` and `focused.screenRect`; the old implementation did not use these, so a request could be queued without targeting any username/password field. It displayed a success Toast before the scheduled request even ran. Opening the browser menu could also remove the HTML input focus before requesting.

Additionally, the app's own "Autofill enabled" preference was easily mistaken for the actual Android Autofill service status, and its Settings shortcut could fall back to a generic Android Settings page without explaining that the service still needed configuring.

## Corrections

- For GeckoView, request `AutofillManager.requestAutofill(view, virtualId, absoluteScreenBounds)` for the current focused virtual HTML field instead of targeting the whole GeckoView.
- Capture the focused virtual field before opening the browser menu and consume the snapshot exactly once after dismissal. Clear it on navigation. No JavaScript injection and no credential copying.
- Defer the system request until after the modal browser menu dismisses; check the current Android service availability, view attachment, visibility and Autofill enablement before requesting.
- Return specific user-facing results: service not configured, view unavailable, no focused input, request dispatched, or request failed. A dispatched request is **not** presented as guaranteed autofill success.
- Expose a real Autofill service status row with a manual refresh action under Privacy settings. On OEM devices where a direct Autofill settings intent is unsupported, try input-method settings before falling back to general Settings.
- Keep the global browser toggle and private-tab exclusion from the earlier commit. WebView relies on its built-in Android Autofill virtual-field handling when an HTML input is focused; its public API does not expose focused virtual IDs for the same explicit request used with GeckoView.
- Add pure Kotlin regression tests for the one-shot focus snapshot, including menu focus loss, navigation cleanup and current-input precedence.

## Acceptance

Require AI Workspace unit tests, browser unit tests, and ARM64 Debug build on this exact commit; system Autofill providers and virtual HTML input identification still require a real Android 16 device. Test with an Autofill service actually selected in Android Settings and a username/password form. Secure autofill selection remains dependent on Android and the password provider.
