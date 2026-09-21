## 0.4.1

- 新增 ChatGPT 对话绑定模式：YagaYHub 可调用 YBrowser 打开 ChatGPT，用户选中具体聊天后通过顶部绑定条将当前聊天 URL/标题回传给对应 GitHub 项目。
- 仅具体 ChatGPT 对话页面可绑定，共享链接不会被当作项目绑定目标。

# YBrowser

YBrowser is YagaY's standalone Android browser with a shared browser UI over two Android rendering engines.

## Engines

- Mozilla GeckoView 155
- Android System WebView

The browser UI, tabs, settings, bookmarks, history, permissions, downloads and public API are engine-neutral. Private tabs always use a real Gecko private session.

## Implemented in 0.4.0

### Daily browsing
- Address/search bar with top or bottom placement
- Back, forward, reload, home
- Persistent multi-tab browsing
- Live per-tab engine sessions: switching tabs preserves WebView/GeckoSession state instead of reloading the page
- Per-tab native back/forward history, scroll position, form state and JavaScript state remain alive while the process is running
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
- Visual tab overview with bounded live thumbnails from WebView and GeckoView
- Private-tab previews remain memory-only and are never persisted
- Fullscreen-media Picture-in-Picture auto-entry on Android 12+
- Translate current page
- View page source
- Print / save page as PDF

### Library and state
- Bookmarks
- History
- History clearing
- Persistent settings
- Clear WebView + Gecko cookies/site data
- Private tabs excluded from history and persisted tab state
- Built-in download library backed by Android DownloadManager
- Download status/progress, open, share, retry, cancel/delete and completed-record cleanup
- Dual-engine Reader mode with one shared article model
- WebView Reader extraction through the live document
- GeckoView Reader extraction through YBrowser's built-in per-session WebExtension bridge
- Full-screen Reader typography with adjustable text size

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
- Per-site overrides for JavaScript, cookies, tracking protection and text scale
- Per-site camera / microphone / location decisions with Allow once / Always allow / Always block
- Site permission reset from the site settings panel
- Long-press link/image actions: new tab, background tab, copy, share, download/save image and external open
- Unified JavaScript alert / confirm / prompt / before-unload / repost UI
- HTTP / Gecko authentication prompt handling
- Android MediaSession + mediaPlayback foreground service for active web media
- Notification and lock-screen Play / Pause / Stop routed back to the media-owning tab
- Recently-playing tab retains media controls even after switching to another tab
- Scoped YouTube / YouTube-nocookie background visibility protection for both engines
- Foreground media service starts only after real playback begins

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

## Large optional subsystems not bundled into 0.4.0

These are separate product-sized features rather than normal browser basics and are intentionally not represented by fake menu items:

- Firefox WebExtension manager and bundled uBlock Origin
- Offline Reader library / text-to-speech
- Userscript catalog/editor
- Isolated multiple browser profiles
- Cross-device encrypted sync server
- Tab stacks and snoozing
- Navigation Trails
- Link Peek live preview
- In-app mini-player
- Cast
- Full EasyList/EasyPrivacy/uAssets filter engine and rule editor

They can be added on top of the current engine-neutral architecture without moving browser code back into YagaYHub.
