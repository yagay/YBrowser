# YBrowser

YBrowser is YagaY's standalone Android browser with a shared browser UI over two Android rendering engines.

## Engines

- Mozilla GeckoView 155
- Android System WebView

The browser UI, tabs, settings, bookmarks, history, permissions, downloads and public API are engine-neutral. Private tabs always use a real Gecko private session.

## Implemented in 0.2.0

### Daily browsing
- Address/search bar with top or bottom placement
- Back, forward, reload, home
- Persistent multi-tab browsing
- New-window / target=_blank routing into a new YBrowser tab
- Private tabs with Gecko private sessions
- Restore previous tabs
- Desktop/mobile website mode
- Find in page
- Share / copy URL / external open
- Android default-browser integration
- Safe system-bar layout
- Fullscreen HTML5 media
- Web file upload
- Camera, microphone and location permission flow
- Android download manager integration
- Printing on System WebView and GeckoView

### Library and state
- Bookmarks
- History
- History clearing
- Persistent settings
- Clear WebView + Gecko cookies/site data
- Private tabs excluded from history and persisted tab state

### Appearance
- System / light / dark / AMOLED
- Material 3 dynamic color
- Top / bottom address bar
- Web text scale 50–200%

### Search
- Google
- DuckDuckGo
- Bing
- Brave
- Ecosia
- Startpage
- Qwant
- Kagi
- Perplexity

### Privacy and site behavior
- JavaScript toggle
- Cookie toggle
- Default desktop mode
- GeckoView native Enhanced Tracking Protection: Off / Standard / Strict
- WebView local tracker-domain blocking: Off / Standard / Strict
- Unsafe/non-http schemes routed to the relevant Android app

## Public open-url API

Package:

`com.yagay.YBrowser`

Action:

`com.yagay.YBrowser.action.OPEN_URL`

Extra:

`com.yagay.YBrowser.extra.URL`

Example:

```kotlin
val intent = Intent("com.yagay.YBrowser.action.OPEN_URL")
    .setPackage("com.yagay.YBrowser")
    .putExtra("com.yagay.YBrowser.extra.URL", "https://github.com/")
startActivity(intent)
```

YBrowser also accepts normal Android `ACTION_VIEW` HTTP/HTTPS intents.

## Architecture

```text
YBrowser UI / state
├── tabs
├── bookmarks
├── history
├── settings
├── permissions
└── downloads
        │
        ▼
BrowserEngine
├── GeckoView engine
└── System WebView engine
```

Engine-owned cookies and native back/forward state remain separate when switching engines. Shared YBrowser data such as tabs, history, bookmarks and settings stays available.

## Open-source references

YBrowser's implementation is its own codebase. Architecture and feature design are informed by mature open-source Android browsers, especially:

- Candy Browser — dual-engine browser architecture and modern browser-product patterns
- Mozilla GeckoView / Firefox Android — Gecko engine APIs, private sessions, permissions, printing and tracking protection
- DuckDuckGo Android — compact mobile browser interaction patterns
- Lightning / SmartCookieWeb — Android WebView browser patterns

## Large optional subsystems not bundled into 0.2.0

These are separate product-sized features rather than normal browser basics and are intentionally not represented by fake menu items:

- Firefox WebExtension manager and bundled uBlock Origin
- Reader Studio / offline article saves / text-to-speech
- Userscript catalog/editor
- Isolated multiple browser profiles
- Cross-device encrypted sync server
- Tab stacks, snoozing and visual tab previews
- Navigation Trails
- Link Peek live preview
- Picture-in-picture / in-app mini-player / media notification controls
- Cast
- Full EasyList/EasyPrivacy/uAssets filter engine and rule editor

They can be added on top of the current engine-neutral architecture without moving browser code back into YagaYHub.
