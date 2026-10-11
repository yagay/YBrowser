# YBrowser further optimization — 2026-10-11

Baseline: `e9e586adea334e885616b5546a0a328e935fb0f9` (Actions #1387 passed).

## Changes and rationale

1. **Navigation history durability:** the previous navigation `LaunchedEffect` could be cancelled while waiting for the history mutex, so the non-cancellable IO block would never run. The entire lock acquisition and write now run in `withContext(NonCancellable)`. History state is applied only if the browser still uses the originating profile.
2. **Tab persistence:** JSON serialization and tab snapshot writes now happen on `Dispatchers.IO` using the immutable tab list snapshot, ordered through a mutex. The key includes the effective profile, so switching profiles cannot silently skip a needed save.
3. **Back navigation:** adds missing dialog/sheet priorities for WebDAV replacement confirmation, data deletion confirmation, pending close-tab confirmation, passwords, WebDAV, security details, navigation trails and menu.
4. **Encrypted passwords:** no credential vault decryption during initial browser Compose state initialization. The passwords sheet requests credentials on an IO dispatcher when opened; deletion and full clearing run on IO with disabled busy controls and loading/error states.
5. **Preview cleanup:** loading cached tab previews no longer deletes an in-progress AtomicFile `.new` file or a newly-saved preview for a tab created after the caller captured its tab list. Orphan cleanups only apply to old files; a pure Kotlin test covers stale backups, allowed tabs and writer sidecars.
6. **WebDAV:** upload, connection test and restore UI handlers now clear their busy state in `finally`, surface unexpected errors, and guard repeated operations.

## Verification requirements

GitHub Actions should run `:ai-workspace:testDebugUnitTest`, `:app:testDebugUnitTest`, and ARM64 `assembleDebug`. A successful build does not replace device validation of back navigation, IME focus, GeckoView/WebView session retention, and credential restore.

7. **History clear ordering:** the History sheet and Clear Data dialog now queue clear operations through the same history mutex as navigation writes. This prevents pending asynchronous history updates from restoring records immediately after a user clears the history.
