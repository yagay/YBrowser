# YBrowser Loader · standalone branch

The `browser-loader-v1` branch is a **separate lightweight Android project**, not a build of
DuckDuckGo, Cromite, Brave or their Chromium source trees. It uses the modern
LSPosed **API 102**. The official, unmodified browsers remain installed.

## What is implemented

- LSPosed entry (modern `META-INF/xposed` format), scoped to `org.cromite.cromite`
  and `com.brave.browser`.
- Single `Activity.onPostResume()` lifecycle hook with a small floating **module center** button
  when the resumed Activity extends Chromium's `ChromeTabbedActivity` or Brave's `BraveActivity`.
  No undocumented Chromium toolbar hook is claimed.
- A standalone, native-Android UI listing installed browsers and feature APKs, with
  manual enable/disable switches.
- Separate Android Library **plugin-api** and **sample-plugin** modules to demonstrate
  compiling a new feature without rebuilding the Loader or Chromium.
- Signature-only plugin trust: the Loader app only enumerates
  `com.yagay.ybrowser.plugin.*` APKs registered with
  `com.yagay.ybrowser.loader.FEATURE`, signed with **the same certificate** as Loader.
  Plugins are **off by default** and need explicit user opt-in.
- Read-only `ContentProvider` bridge restricted by calling UID to Cromite/Brave and the Loader
  itself. Only enabled, same-signed APKs can be served.
- SHA-256 checksum verification after copying feature code into the browser's private code
  cache, marking files read-only before using `DexClassLoader`.
- Per-feature catch/log so a synchronous plugin callback failure does not skip other callbacks.

## Build using GitHub Actions

This branch's workflow builds **two** small Android Debug APKs (Gradle 8.9 / AGP 8.7.3 /
JDK 17 / compileSdk 35); it does not download or build Chromium. Download both artifacts:

- `app-debug.apk`: YBrowser Loader and LSPosed entry.
- `sample-plugin-debug.apk`: separately installable feature.

**Signing:** both APKs must be signed by the **same signing key**.
The two APKs built in one CI run share that run's Android debug signing setup.
Different CI runs can generate different debug keystores, so for reproducible updates
configure a persistent signing key securely; never commit its private key to GitHub.
Debug APKs are for experiments only.

## First-run verification

1. Install Cromite or Brave from its official source.
2. Install both APKs from the same GitHub Actions run.
3. Enable **YBrowser Loader** in an LSPosed implementation supporting API 102. Select the
   Cromite/Brave scope and restart that browser.
4. Open Loader UI and enable "YBrowser Sample Feature".
5. Open the browser's normal tab Activity. The blue **module center** button opens the
   Loader UI. The green **Y+** chip appears after successful loading; clicking it displays a toast.
6. Disable the sample, force-stop the browser, and reopen it to verify no sample chip appears.

## Developing your own feature APK

Create an Android application module with a unique applicationId starting with
`com.yagay.ybrowser.plugin.`. Use `compileOnly project(':plugin-api')` or compile against
the corresponding API AAR. Do not package a duplicate BrowserFeature class.

Register a `Service` using this intent action in your APK's manifest:

```xml
<service android:name=".FeatureDescriptorService"
    android:exported="true"
    android:permission="com.yagay.ybrowser.loader.permission.DISCOVER">
    <intent-filter>
        <action android:name="com.yagay.ybrowser.loader.FEATURE"/>
    </intent-filter>
    <meta-data android:name="ybrowser.feature.class"
        android:value="com.yagay.ybrowser.plugin.myfeature.MyFeature"/>
</service>
```

Your entry class implements `com.yagay.ybrowser.api.BrowserFeature` with a public no-arg
constructor. The Loader invokes `onBrowserActivityResumed(Activity, String)` on the main
thread. The descriptor service is never started by Loader.

Recompile only your new plugin module, sign it with the **same key**, install it, enable it in
Loader settings, then **restart the browser** for reliable activation. The Loader APK does not
need to be rebuilt when the ABI remains unchanged.

## Limitations and safety

- This is a **development proof-of-concept**. Real Cromite/Brave installation and Android
  runtime smoke tests are still required. CI compile success alone does not validate hooks.
- A plugin executes **inside the browser process**, inheriting its sensitive permissions and
  page access. Install only code you audited, never unsigned/untrusted external APKs.
- No ability to hot-swap already-loaded DEX; updates require browser process restart.
- Chromium's private Java classes, obfuscation, and app lifecycle can change. A generic
  lifecycle hook cannot guarantee an actual desktop tab strip, extensions engine, JNI/C++
  modifications, autofill fixes, or file chooser fixes.
- The button is an **in-page floating entry**, not an injected item into Chromium's actual
  three-dot menu. That menu requires version-specific UI hooks, planned separately.
- A bad plugin can still crash Chromium asynchronously; synchronous callback exceptions
  are caught, not a security sandbox.
- The provider intentionally does not expose a generic "load from Downloads" interface.
  Random downloaded APKs are never executed. Code is only loaded from trusted installed
  packages with matching APK signatures and SHA-256 validation.

## Source references

- Modern API 102: https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API
- LibXposed API: https://github.com/libxposed/api
- Chromium Activity: `org.chromium.chrome.browser.ChromeTabbedActivity` (upstream manifest)
- Brave Activity: `org.chromium.chrome.browser.BraveActivity` (Brave Core sources)
- Android code loading safety: https://developer.android.com/privacy-and-security/security-tips

## Project modules

```
app/           Loader UI, LSPosed entry, trusted catalog provider and DEX loader
plugin-api/    Shared BrowserFeature Java/Android library (compileOnly in plugins)
sample-plugin/ Standalone signed APK demonstrating custom browser UI
```


## Browser Controls — real feature module (new)

A third **independently compiled** signed APK is now built alongside Loader and sample:

- \`browser-controls-debug.apk\` — a horizontally scrollable tab strip with actual
  Chromium TabModel titles; tap to select, × to close, + to open a tab;
  back, forward, reload; live refresh; strip size/position and collapse menu.
- It reflects public Java surfaces from Chromium's \`ChromeActivity\`, \`TabModel\`,
  \`TabModelUtils\`, \`Tab\`, \`TabCreator\`, \`TabRemover\` and \`TabClosureParams\`.
  No Chromium binary fork needs compiling.
- The strip defaults near the bottom rather than hiding the native address bar.
  Top placement is optional; on-screen overlay position is not integrated into
  Chromium compositor insets, and may cover content or toolbars in some versions.
- If Chromium changed a method or the model is not initialized, controls fail closed
  with "Not supported" instead of changing private fields.

**Install Loader, Sample and Browser Controls from the SAME workflow artifact**
so Android debug signatures match, then enable "YBrowser Browser Controls" in
Loader and restart Cromite/Brave. You may leave Sample disabled.

**Honest limitations:** no native tab reordering or drag/drop yet. HTML/CSS force
dark, Chromium's own toolbar resizing, extension engine, full file-picker repairs,
and menus inserted inside Chromium's built-in three-dot menu require additional
verified version-specific hooks. This first real plugin is a *floating* control
strip; it is not Chromium's native desktop tab strip. Build success is not device
verification. Do not claim broad-version compatibility without actual runtime logs.

Upstream API references:
- https://chromium.googlesource.com/chromium/src/+/HEAD/chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java
- https://chromium.googlesource.com/chromium/src/+/HEAD/chrome/android/java/src/org/chromium/chrome/browser/tabmodel/TabModelUtils.java


## Multi-tab troubleshooting

Browser Controls is a **third APK** and must be installed separately from the
Loader and Sample. Install all from the SAME successful CI run, open Loader,
enable Browser Controls, then force-stop and reopen the browser. The Loader
does not include Browser Controls classes within its own APK.

The floating browser controls now use a TWO-ROW layout: the top row is
dedicated to horizontally scrollable tabs and a plus button, and the second
row contains navigation and settings. Tab width is adjustable and model
failures expose a diagnostic message rather than silently reporting an
unavailable API. Supports both ChromeActivity.getCurrentTabModel and the
TabModelSelector.getCurrentModel fallback. Reflective API fixes include the
integer FROM_CHROME_UI launch type and TabModel.setIndex fallback.

Successful GitHub CI proves Java compilation and APK signing, **not** that
all public Chromium Java methods are retained in each installed Cromite or
Brave version. If the overlay is still missing, inspect Loader's plugin
installation state and LSPosed scope. If tabs show an error, open the tab
diagnostic message and capture logcat lines tagged YBrowserTabs.
