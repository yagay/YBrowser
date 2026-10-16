# YBrowser Loader: Chromium source-to-hook audit

## Scope and reality

This document audits `browser-loader-v1` against public Chromium Java sources
and the Cromite / Brave upstream build model. It is **not** a claim that every
installed Chromium, Cromite, or Brave APK has identical Java method signatures.
Actual APK/Dex (and, when applicable, published mappings) must be checked for
the exact installed version before declaring an API compatible.

Current Xposed entrypoint is `Activity.onPostResume` in
`BrowserXposedEntry`. This is a stable Android-framework lifecycle hook,
**not** a Chromium `TabModel` method hook. The separate signed
`browser-controls` feature invokes the browser's Chromium Java tab APIs
from the hooked browser process. The floating tab strip is not a native
Chromium compositor tab strip.

## Source-based call map

| Feature | Chromium source entry | YBrowser handling |
| --- | --- | --- |
| Current tabs | `ChromeActivity.getCurrentTabModel()`, then `TabModel.getCount/index/getTabAt` | Read current model; selector/supplier fallbacks after initialization |
| Switch tab | `TabModelUtils.setIndex(model,index)`; `TabModel.setIndex(index, TabSelectionType.FROM_USER)` | Prefer upstream helper; resolve FROM_USER by type (legacy int or modern enum) |
| New tab | `TabCreatorManager.TabCreator.launchNTP()` | Prefer upstream browser-owned launcher; use `launchUrl` only on old builds |
| Close tab | `TabClosureParams.closeTab(tab).build()` plus `TabRemover.closeTabs(...)` | Use browser-owned remover and native group confirmation, old model fallback |
| Back/forward/reload | Current `Tab` methods | Execute only after acquiring the active tab; report operation-specific failures |

**Why prior code was wrong**: It hard-coded `TabSelectionType.FROM_USER`
as integer 3 and assumed that the version-specific implementation would
always expose a two-int `setIndex` signature. Chromium has used a generated
Java enum form of TabSelectionType. Prior tab creation also manually called
`launchUrl("chrome://newtab/", 2)` instead of the provided `launchNTP()`.

## Fork source and version alignment

- Cromite is an ordered set of patches against a pinned Chromium
  tag identified by its `build/RELEASE`. Review its patch list and the
  *specific* installed APK's Chromium version before adopting a patch-specific
  method or signature.
- Brave Core carries Chromium customizations, subclasses, patches and
  `chromium_src` overrides. Inspect the exact Brave build/Chromium revision,
  not merely current upstream Chromium HEAD.
- Chromium Java methods may be renamed, removed, moved, or obfuscated by a
  fork's shipping configuration. A source-level method name is **not**
  a guarantee it survives in installed Dex.
- Android framework lifecycle hooks can be a stable entrypoint. Real
  browser-specific TabModel/TabModelSelector observer hooks require validated
  Dex class names, method signatures, lifecycle, and a safe unhook/restart
  strategy.

## Device validation checklist

1. Record target package name, browser version, and Chromium version.
2. Confirm module is enabled in LSPosed for that browser, controls feature
   APK is installed, same-signed, and enabled.
3. Verify tab-model initialization and the visible Activity class name.
4. On an installed APK, inspect the actual `TabModel`, `TabCreator` and
   `TabRemover` method signatures if actions fail.
5. Verify normal and incognito tabs separately; check launch, selection,
   close, persistence/recovery, group confirmation, and Activity recreation.
6. Only after that consider migrating the generic framework lifecycle
   hook to a targeted Chromium observer. Never silently treat CI compilation
   as end-to-end runtime verification.

## Source references

- Chromium ChromeActivity API:
  https://chromium.googlesource.com/chromium/src/+/c0ab132ab0e7612fc46965bdbe5b63a5f2549c81/chrome/android/java/src/org/chromium/chrome/browser/app/ChromeActivity.java
- Chromium TabModelImpl:
  https://chromium.googlesource.com/chromium/src/+/fb4ce10866412b0ecddb9e6b207fe643314f491f/chrome/android/java/src/org/chromium/chrome/browser/tabmodel/TabModelImpl.java
- Chromium TabCreator and launchNTP migration:
  https://chromium.googlesource.com/chromium/src/+/4a7f224d18..6e83997c8b
- Chromium TabRemoverImpl:
  https://chromium.googlesource.com/chromium/src/+/16881725782676a372a6b3c84f4a04f7cc346ae1/chrome/android/java/src/org/chromium/chrome/browser/tabmodel/TabRemoverImpl.java
- Cromite build process: https://github.com/uazo/cromite/blob/master/docs/HOW_TO_BUILD.md
- Brave Chromium patching model:
  https://github.com/brave/brave-core/blob/master/docs/patching_and_chromium_src.md
