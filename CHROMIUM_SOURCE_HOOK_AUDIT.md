# Chromium source and Hook audit (single-APK YBrowser)

The one-APK `browser-loader-v1` build bundles Browser Controls as a Gradle
library, rather than distributing feature APKs. A single libxposed API 102
entrypoint hooks `Activity.onPostResume`, checks the scoped browser Activity,
reads YBrowser's settings from a restricted provider, and instantiates the
built-in BrowserControlsFeature.

## What is sourced versus what remains to verify

- Tab enumeration: ChromeActivity current model and TabModel
  getCount/index/getTabAt.
- Tab selection: TabModelUtils.setIndex or TabModel.setIndex(int,
  TabSelectionType.FROM_USER). The latter may use an enum rather than
  the old int @IntDef.
- New tab: TabCreator.launchNTP(), with legacy launchUrl fallback.
- Close: TabClosureParams/TabRemover and a legacy TabModel fallback.
- Navigation: current Tab back, forward, and reload.

The **source-based Java API calls** do not automatically mean the app has a
specific `TabModel` hook. Installed Cromite/Brave variants can rename,
obfuscate or patch Chromium classes and methods. The stable Android
lifecycle hook is currently the only installed hook; future native event
observers need exact version and Dex inspection. Run
`⋮ → 浏览器接口诊断 → 复制报告` to obtain the actual loaded Java
method signatures and browser version before designing more targeted hooks.

Sources:
- Chromium: https://chromium.googlesource.com/chromium/src/
- Cromite: https://github.com/uazo/cromite
- Brave Core: https://github.com/brave/brave-core
