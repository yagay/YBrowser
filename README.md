# YBrowser — one APK (browser-loader-v1)

YBrowser is an **all-in-one LSPosed API 102 Android module** that enhances
existing Cromite / Brave installations. It is not a standalone Chromium
engine or a replacement browser. All the module's Android UI, native Android
lifecycle hook, Chromium tab bar and runtime diagnostics are compiled into
**one installable APK**.

## Build

GitHub Actions builds only `app:assembleDebug` and uploads
`app-debug.apk` inside the `YBrowser-AllInOne-ARM64-compatible-Debug`
artifact. `browser-controls` is an Android **library dependency** bundled
inside the app; it is **not installed independently**. There is no sample
plugin or external plugin loader.

## Install and use

1. Install `app-debug.apk` from one successful CI run.
2. Enable YBrowser in LSPosed (modern API 102), scoped to
   `org.cromite.cromite` and/or `com.brave.browser`.
3. Open YBrowser once and turn on **启用多标签栏** (on by default).
4. Fully stop and reopen the target browser. The tabs and navigation
   controls attach within the browser process.
5. Open the tab strip's ⋮ menu and select **浏览器接口诊断** to inspect
   runtime method signatures if an action fails; the report is copyable.

An earlier `browser-loader-v1` version used a separate Loader APK, Sample
Plugin APK, and Browser Controls APK with matching signatures. These are
obsolete. The applicationId stays `com.yagay.ybrowser.loader` to support
upgrades signed by the same certificate. If signing keys differ, Android
will refuse an upgrade; uninstalling the earlier APK loses its settings.

## How the one-APK architecture works

- `app` is the only installable module with a settings screen, a limited
  read-only settings provider, and the libxposed API 102 entrypoint.
- `browser-controls` is a library bundled into `app`, not a second APK.
- `BrowserXposedEntry` hooks Android `Activity.onPostResume`, confirms
  the browser Activity, consults settings via a UID-restricted provider, and
  invokes bundled `BrowserControlsFeature` directly.
- The in-browser floating tab strip accesses the currently loaded Chromium
  Java `TabModel` and `TabCreator` via reflection. This does **not** claim
  to hook the native Chromium compositor or C++ tab internals.
- The settings provider can be read only from YBrowser's UID or recognized
  Cromite / Brave UIDs. No code APK is loaded from external apps.

## Known limitations

A successful compilation proves packaging, not compatibility with all
Cromite/Brave versions. To make a stable browser-specific hook, validate the
installed browser's Chromium revision, class/method signatures and lifecycle.
The floating strip may cover content depending on device layouts.

See [CHROMIUM_SOURCE_HOOK_AUDIT.md](CHROMIUM_SOURCE_HOOK_AUDIT.md).
