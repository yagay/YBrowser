# YBrowser

YBrowser is YagaY's standalone Android browser.

## Goals

- Modern Material 3 browser UI inspired by gesture-first browsers such as Candy Browser.
- Dual engine foundation: Android System WebView and Mozilla GeckoView.
- Stable public Intent API so YagaYHub and other YagaY apps can open URLs without embedding a browser engine.

## Public open-url API

- Package: `com.yagay.YBrowser`
- Action: `com.yagay.YBrowser.action.OPEN_URL`
- Extra: `com.yagay.YBrowser.extra.URL`

Example:

```kotlin
val intent = Intent("com.yagay.YBrowser.action.OPEN_URL")
    .setPackage("com.yagay.YBrowser")
    .putExtra("com.yagay.YBrowser.extra.URL", "https://github.com/")
startActivity(intent)
```

YBrowser also accepts normal Android `ACTION_VIEW` HTTP/HTTPS intents.

## Engine model

The browser chrome is independent from the rendering engine. The current foundation supports:

- Android System WebView
- Mozilla GeckoView

Switching engine reloads the current tab URL in the newly selected engine. Engine-owned cookies and native back/forward state are intentionally separate.
