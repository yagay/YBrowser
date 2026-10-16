# YBrowser Chromium source-backed hook audit

## Scope, pinned upstream sources and limitations

This audit is based on **actual upstream source files**, not inferred Java method names:

- Cromite `master` at `uazo/cromite@3724efc6ede3`: `build/RELEASE` says **Chromium 153.0.8010.37**.
- Brave Core `master` at `brave/brave-core@f18cc7fac9a3`: `package.json -> config.projects.chrome.tag` says **Chromium 156.0.8078.39**.
- Chromium Android Java files at those **two exact release tags**, linked below.
- Brave's own `BraveTabCreator.java` overrides `launchUrl(String, @TabLaunchType int)`; its `BraveTabCollectionTabModelImplBase.java` alters the model implementation by bytecode patching. These details are important to avoid assuming Brave is unmodified Chromium.

**These upstream branches are not evidence of the versions actually installed on the user's device.** The diagnostic report needs to record the installed APK package/version and loaded method signatures. Production builds may obfuscate or remove Java symbols. A matching source method does not prove matching shipped Dex.

## Verified API contracts in Chromium 153 AND Chromium 156

| Purpose | Source-defined API | Actual implementation |
| --- | --- | --- |
| Model readiness | `ChromeActivity.areTabModelsInitialized()` | Boolean gate. Do **not** call `getTabModelSelector()` early; it can throw |
| Lifecycle notification | `ChromeActivity.getTabModelSelectorSupplier()` | `MonotonicObservableSupplier.addSyncObserverAndPostIfNonNull(Callback)` |
| Tabs list | `TabModelSelector.getCurrentModel()` / `TabModel.getCount/index/getTabAt` | Source-backed model reads |
| Model changes | `TabModelSelector.addObserverToAllModels(TabModelObserver)` | Observer is an interface, with `didSelectTab`, `didAddTab`, `tabRemoved`, `onActiveChanged`, etc |
| Selector state | `TabModelSelector.addObserver(TabModelSelectorObserver)` | `onTabStateInitialized`, `onNewTabCreated`, `onDestroyed` |
| Titles | `Tab.addObserver(TabObserver)` | `onTitleUpdated`, `onUrlUpdated` |
| Select tab | `TabModelUtils.setIndex(TabModel,int)` | Calls `TabModel.setIndex(index, TabSelectionType.FROM_USER)` internally |
| Create tab | `ChromeActivity.getCurrentTabCreator()` then `TabCreator.launchUrl(String,int)` | Use `UrlConstants.NTP_URL` and `TabLaunchType.FROM_CHROME_UI` |
| Close tab | `TabClosureParams.closeTab(Tab).build()` -> `TabModel.getTabRemover().closeTabs(params,true)` | Native group confirmation allowed |
| Navigation | `Tab.canGoBack/goBack/goForward/reload` | Source-defined browser Java API |

### Correcting previous guesses

1. **Do not call `TabCreator.launchNTP()`** for these revisions. Both pinned `TabCreator.java` sources expose `launchUrl(String, int)` and **no** `launchNTP()`. That method appeared in older source revisions.
2. **Do not assume `chrome://newtab/` is the new-tab URL.** In both pinned versions, `UrlConstants.NTP_URL` is **`chrome-native://newtab/`**; `NTP_NON_NATIVE_URL` is the other value.
3. **Do not hard-code `TabSelectionType.FROM_USER = 3`** or cast to a Java enum. `TabModelUtils.setIndex(TabModel,int)` is the actual supported helper in the pinned source, and forwards the appropriate type itself.
4. Avoid guessed `getTabCreatorManager`, `launchNTP`, or `TabModel.setIndex` variants. If shipped Dex removes a sourced method, report the exact error and obtain runtime signatures.

## Hook architecture

`BrowserXposedEntry` installs one scoped Android `Activity.onPostResume` LSPosed hook solely as a safe entry to the browser process. The bundled `BrowserControlsFeature` then registers **native Chromium observers** directly using the verified public Java interfaces. This is not a bytecode hook into a private `TabModelImpl` method. That would be more version-dependent and could cause crashes.

`ChromiumTabObserver` waits for the source-defined `getTabModelSelectorSupplier()` callback, subscribes to model/selector observers, adds `TabObserver` for tab titles, and unregisters all callbacks on UI detach. It no longer polls every second.

`ChromiumTabBridge` calls only the verified source signatures for actions and emits explicit errors when the installed binary differs. All calls execute in the browser process; no separately signed controls/plugin APK is needed.

## Source links

Cromite version and patches:
- https://github.com/uazo/cromite/blob/master/build/RELEASE
- https://github.com/uazo/cromite/blob/master/build/cromite_patches_list.txt

Brave Chromium pin and fork changes:
- https://github.com/brave/brave-core/blob/master/package.json
- https://github.com/brave/brave-core/blob/master/android/java/org/chromium/chrome/browser/tabmodel/BraveTabCreator.java
- https://github.com/brave/brave-core/blob/master/android/java/org/chromium/chrome/browser/tabmodel/BraveTabCollectionTabModelImplBase.java

Chromium pinned source (replace `153.0.8010.37` by `156.0.8078.39` to compare Brave's pin):
- https://github.com/chromium/chromium/blob/153.0.8010.37/chrome/android/java/src/org/chromium/chrome/browser/app/ChromeActivity.java
- https://github.com/chromium/chromium/blob/153.0.8010.37/chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabModelSelector.java
- https://github.com/chromium/chromium/blob/153.0.8010.37/chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabModelObserver.java
- https://github.com/chromium/chromium/blob/153.0.8010.37/chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabModelUtils.java
- https://github.com/chromium/chromium/blob/153.0.8010.37/chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabCreator.java
- https://github.com/chromium/chromium/blob/153.0.8010.37/chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabRemover.java
- https://github.com/chromium/chromium/blob/153.0.8010.37/chrome/browser/tab/java/src/org/chromium/chrome/browser/tab/Tab.java
- https://github.com/chromium/chromium/blob/153.0.8010.37/components/embedder_support/android/java/src/org/chromium/components/embedder_support/util/UrlConstants.java
- https://github.com/chromium/chromium/blob/153.0.8010.37/base/android/java/src/org/chromium/base/supplier/MonotonicObservableSupplier.java

## Acceptance testing on real device

Before claiming complete compatibility: record installed browser version; run diagnostics
and confirm the selector supplier and model observer are attached; add/select/close
multiple regular tabs, repeat incognito, verify back/forward/reload, titles, rotation,
browser restart, browser updates and native tab-group closure dialogs. If any sourced
interface is absent in APK Dex, stop that feature and capture the diagnostic report
instead of guessing a replacement signature.
