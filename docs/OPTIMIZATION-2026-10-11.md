# YBrowser performance and maintainability pass — 2026-10-11

Baseline: `1d7743da1f3b7dd6a19c8f27bd7cf1dbb3fcfcfd` (Actions #1384 passed).
Scope: source review across the supplied three-module snapshot, with current GitHub source checked for modified files. This change focuses on verified main-thread and unbounded-IO problems rather than replacing engines.

## Optimizations

1. Shared `rememberRepositoryList` provides cancellable IO-backed loading, refresh and last-good-data retention for Profile, user script, web app and snoozed-tab sheets. No initial disk reads occur during Compose composition.
2. The home screen search suggestions now check Content-Length and read at most 256 KiB. Previously `readText().take(256 KiB)` could allocate the entire remote response first.
3. A shared `BoundedTextReader` includes size-bound tests (UTF-8, exact-limit and oversized payload).
4. Navigation diagnostic logs are queued on a dedicated single-thread executor; high-frequency logging no longer synchronously appends or rotates files on the caller/UI thread. Appends, clearing and reads remain ordered. The YagaYHub log viewer loads the full diagnostics through `Dispatchers.IO` and cancels stale loads on clear/re-open.
5. PWA icon-cache reads recognize AtomicFile .bak recovery and cap in-memory reads even if a file changes between size check and open.

## Validation

- Pure Kotlin bounded input implementation compiled and passed an executable smoke harness.
- **Android Gradle and instrumentation tests still require CI/device validation.**
- Verify navigation/restore, typing, split screen, auto-hide, keyboard, both engines, loading and refresh errors on-device. PWA bitmap rendering and long-lived retained-tab Surface behavior are not proven by source review alone.

## Future substantial refactoring

The large `BrowserApp.kt` and `BrowserEngine.kt` classes should be decomposed into navigational/presentation and engine-lifecycle modules with regression tests, not via an untested wholesale rewrite. Import backup transactional rollback and full GeckoView/WebView surface/IME tests remain separate acceptance items.
