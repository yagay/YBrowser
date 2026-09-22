## 0.6.0

- 保留 GeckoView / System WebView 双内核、YagaYHub 接口、弹窗浏览器、下载、Reader、媒体会话等现有功能，并继续使用单排固定导航栏。
- Firefox 扩展管理：Mozilla 签名 XPI 安装、启停、更新、卸载、隐私模式权限、选项页，以及 uBlock Origin 快速安装入口。
- 隐私报告：记录每个标签页实际拦截的广告 / 分析 / 社交 / 指纹 / 自定义过滤内容；Gecko 标准/严格保护加强，支持参数剥离和 Cookie 隔离。
- 高级标签管理：搜索、网格/列表、固定、复制、恢复最近关闭、重新排序、关闭其他/未固定、休眠标签、定时唤醒通知、持久化标签分组 / Stack。
- 浏览器 Profiles：默认 Profile 兼容旧数据；Gecko 使用 contextId 隔离 Cookie/localStorage；支持 AndroidX WebKit MULTI_PROFILE 的设备对 System WebView 使用真实独立 Profile；标签、收藏、历史、站点设置和权限也按 Profile 隔离。
- 原生新标签页：本地搜索入口、快捷收藏、最近访问；地址栏使用收藏和本地历史联想，不向额外建议服务发送输入内容。
- Reader Studio：离线保存文章、离线文章库、文字朗读 / 停止朗读。
- Permission Radar：摄像头、麦克风、位置可按网站直接查看和修改为每次询问 / 允许 / 阻止。
- 用户脚本 / Toppings：本地新增、编辑、启停，按域名匹配，并在 WebView 与 GeckoView 中执行。
- 自定义过滤：WebView 网络级域名阻断，Gecko 页面资源清理；完整 Gecko 网络过滤可直接使用 Firefox 扩展。
- 媒体增强：网页媒体迷你播放器固定占用布局空间，不覆盖网页，可播放 / 暂停 / 停止并跳回媒体标签。
- 收藏、历史和下载管理新增搜索；下载支持全部 / 进行中 / 已完成 / 失败筛选。
- 完整 JSON 备份 / 恢复：设置、Profiles、普通标签、收藏、历史、站点规则/权限、用户脚本、自定义过滤、下载记录、休眠标签和离线 Reader 文章。
- 地址栏保留左右滑动切换标签和 > 命令模式；长按链接支持临时 Link Peek 预览，不写入普通浏览历史。
- 搜索新增 ChatGPT 与可配置 SearXNG；支持 Google Code Scanner 二维码扫码，结果直接按网址或当前搜索引擎处理。
- 自动标签清理可关闭或设置 1 / 7 / 30 天，只清理长期未访问且未固定的普通标签；升级前已有标签不会被立即清理。

## 0.5.0

- 全面重构移动端 UI：更紧凑的 Material 3 地址栏与常用导航快捷操作。
- 三点菜单改为 Bottom Sheet，常用操作以图标网格呈现，高级网页工具保留在下方列表。
- 标签页改为双列卡片网格，保留实时缩略图，并增加全部 / 普通 / 隐私筛选。
- 设置页改为分类入口，分离常规、外观、主页与搜索、网页、隐私与安全、系统和关于。
- 保持 GeckoView / System WebView 双内核、YagaYHub ChatGPT 绑定、下载、阅读模式和媒体功能不变。

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
