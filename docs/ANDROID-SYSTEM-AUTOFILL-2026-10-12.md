# Native Android Autofill in YBrowser — 2026-10-12

Base: `f414bab7aaafbb00064a2292c8a9783042e93484`.

## Native feature implementation
- Added **Settings → Privacy & security → Android system Autofill** toggle, persisted as `system_autofill_enabled` (enabled by default to preserve existing WebView behavior). Turning off removes WebView/GeckoView from Android's Autofill framework.
- Added **Choose system autofill service**, which opens `Settings.ACTION_AUTOFILL_SETTINGS` rather than trying to read password databases or requesting elevated permissions.
- Added **Request system Autofill** to the web-page menu when enabled and not in private mode. Uses Android's `AutofillManager.requestAutofill(view)` after the menu dismisses and only on the current attached view; whether suggestions appear is determined by the configured provider, page form and focus.
- GeckoView uses its supported `GeckoView.setAutofillEnabled(boolean)` API plus Android `importantForAutofill`. WebView uses Android `importantForAutofill`; both respect configuration updates without engine recreation, including preloaded normal tabs and popups.
- **Privacy guard:** private tabs disable system autofill regardless of the global toggle. The existing YBrowser Gecko encrypted credential storage remains separate from Android's selected password-manager service. This change does not transfer saved passwords.
- Pure unit tests cover normal/private and on/off cases. Real Android UI/service integration requires installing APK and testing with a provider configured in Android Settings.

## Caveats
A provider needs to be installed and selected in the Android system settings. Autofill suggestions rely on recognizing a login form (and sometimes a focused username/password field). This functionality is supported by GeckoView and System WebView native virtual input APIs; no LSPosed hook, JavaScript-based password injection or accessibility automation is involved.
