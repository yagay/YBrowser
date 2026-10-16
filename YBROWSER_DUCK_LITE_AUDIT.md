# YBrowser Duck Lite cleanup status

Branch: `browser-v2-rebuild`

This document records a conservative browser-only cleanup of the DuckDuckGo Android
upstream. A feature must not be called "removed" until its implementation is
actually absent from the target variant's dependency graph.

## Completed

- Browser settings no longer expose Duck's device VPN, paid subscription/PIR
  upsells, DDG Sync settings, Duck AI settings, desktop download promotion,
  remote "What's New" messages, vendor feedback, or installation onboarding tips.
- The Settings ViewModel no longer polls device VPN/subscription state every second.
- App startup no longer starts Duck referral attribution retrieval. Unused
  Application injection fields have been detached.
- Settings no longer creates paid upgrade/VPN plugin views or registers the
  DDG feedback activity launcher.
- Direct `:app` runtime dependencies removed for the optional desktop-platform
  implementations, desktop-app-promotion implementation, surveys implementation,
  vendor feedback, Dax prompts, and redundant desktop-platform APIs.
- Duplicate persistent-storage dependency declarations removed.
- Gradle cache enabled explicitly in CI, parallel execution enabled with
  a limited number of workers.

## Still present (intentionally)

The DDG upstream is deeply interconnected. Duck AI engine, DDG account sync,
subscriptions, PIR, remote messaging, device VPN, tracking SDKs and their
indirect dependencies have **not** all been removed from the repository.
Various browser, autofill, onboarding and privacy components still consume
their APIs through Dagger/Metro, resource references or navigation interfaces.

Do not delete these directories blindly. Before removing any additional
feature family:

1. Find its API references across every Kotlin/Java file and Gradle module.
2. Remove navigation paths, injected fields, plugin bindings, workers and
   merged AndroidManifest components for the entire feature.
3. Remove implementation and finally API dependencies once no consumers remain.
4. Build `:app:assembleInternalDebug`, inspect merged manifest, and test
   tab switching, file chooser uploads, download, autofill, WebView,
   privacy mode and process restoration on an Android device.

Core web privacy modules, site permissions, cookies, history, bookmarks,
downloads, WebView, JS messaging, and SERP / omnibar state are intentionally
preserved.

## Important

CI compile success is not a runtime smoke test. The browser is presently an
experimental fork; never claim that all vendor subsystems have been removed,
and do not ship a production APK until device-level regression tests pass.

## Additional standalone source cleanup
The unreferenced upstream example app, benchmarking projects/scripts, upstream
Fastlane publishing pipeline, and upstream developer-assistant guidance were
removed from the V2 source tree. A CI preflight now checks that these modules
remain absent and that browser-essential project dependencies remain wired.
This reduces unrelated project configuration but does not replace a full
runtime dependency audit, nor demonstrate feature-level completion.
