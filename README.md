## 0.7.4

- 修复 GeckoView WebExtension 内容脚本 native messaging：为 AI RPC 与 Reader 内置扩展加入 `nativeMessagingFromContent`，解决日志中的 `Unexpected messaging sender` 和 `rpc-timeout`。
- AI RPC 与 Reader 内置扩展版本从 1.0.0 升到 1.0.1，确保 `ensureBuiltIn()` 在升级 APK 后实际重新安装新的 manifest 权限。
- 修复 AI 网页宿主在 Compose 每次重组时重复 `runtime.attach()` 的问题；GeckoView 现在按窗口稳定挂载，不再产生每秒数百次 `attach/session-reuse`。
- 继续保留桥接诊断日志，可验证 `port-connected → watcher-install → conversation-event → native-applied` 是否完整贯通。

## 0.7.3

- 新增 AI 页面桥接诊断：记录 GeckoSession、WebExtension RPC、watcher 安装、页面消息候选数、推送事件、Native 接收和聊天列表应用结果。
- 导出诊断 ZIP 新增 `ai-bridge-trace.jsonl` / `.1`，不记录聊天正文、Cookie、Token 或文件内容。
- AI Workspace 侧边栏新增“清空诊断日志”，可先清空、复现一次问题，再使用“导出诊断日志”生成干净的分析包。
- RPC 诊断可区分扩展未连接、端口断开、事件未到达、evaluate 超时、DOM 候选为 0、消息角色识别失败、Native 丢弃和成功应用等阶段。

## 0.7.2

- 原生 AI 聊天窗口现在只同步 YagaYHub 已绑定的具体 AI 对话页面内容，不读取 Provider 首页、历史侧栏或其他聊天。
- 绑定 URL 独立持久化；网页模式后续导航不会改变绑定目标，只有当前 Gecko 页面的 host + path 与绑定页一致时才允许同步。
- 新增 `conversationSnapshot()` 页面同步层，将绑定页当前 DOM 中的 user/assistant 对话按顺序恢复到原生聊天列表。
- 进入绑定窗口以及从网页模式切回聊天模式时自动同步；未绑定的新 AI 窗口仍保持本地原生聊天逻辑，不扫描网页历史。

## 0.7.1

- AI Workspace 改为 AIHub 原生聊天工作区：原生消息列表、输入框、附件、发送/停止、Provider 分组和多窗口标签。
- 每个 AI 窗口后台保留一个 GeckoSession；聊天模式隐藏网页而不销毁会话，切到“网页”时直接显示同一个会话，不主动重载。
- ChatGPT、Gemini、Claude、Grok、DeepSeek、Qwen 全部统一使用 YBrowser `browser-core` 的 Gecko 默认 Profile，因此与 YBrowser Gecko 浏览器共享登录状态、Cookie 与站点存储。
- AIHub Provider JS 适配器作为 `ai-workspace` 独立 assets 维护；主浏览器双内核、标签、下载、Reader 等代码不依赖 AI 实现，方便继续合并上游浏览器功能和修复。
- YagaYHub 传入的已绑定 AI 页面会导入原生工作区；普通网页仍走 YBrowser 普通浏览器。

## 0.7.0

- 新增独立 `ai-workspace` library module：AI 会话 UI、Provider 入口、窗口持久化与 Gecko 会话保活均与主浏览器代码分离。
- AI Workspace 与普通 YBrowser 共用 `browser-core` 的 `SharedGeckoRuntime` 默认 Profile；Google/AI 网站登录、Cookie 与站点存储可在同一 YBrowser 安装内复用。
- 新增稳定外部入口 `OPEN_AI` / `OPEN_AI_SESSION` / `OPEN_AI_WEB`，YagaYHub 不需要依赖 YBrowser 内部页面实现。
- AI 窗口切换复用原 GeckoSession，不主动 reload；进程被系统回收后会从已保存的当前 URL + Gecko Profile 缓存恢复。
- 普通浏览器仍保留 GeckoView / System WebView 双内核与原有 UI/功能；AI 模块只通过公开的 `browser-core` 接口接入，减少后续合并上游功能或修复时的冲突。

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

## Public AI Workspace API

Package:

`com.yagay.YBrowser`

Actions:

- `com.yagay.YBrowser.action.OPEN_AI` — open the AI workspace.
- `com.yagay.YBrowser.action.OPEN_AI_SESSION` — focus a persisted AI window.
- `com.yagay.YBrowser.action.OPEN_AI_WEB` — open an AI web page in the retained workspace.

Stable extras:

- `com.yagay.YBrowser.extra.URL`
- `com.yagay.YBrowser.extra.AI_PROVIDER_ID`
- `com.yagay.YBrowser.extra.AI_WINDOW_ID`
- `com.yagay.YBrowser.extra.CHAT_TARGETS_JSON`

The AI activity lives in the optional `ai-workspace` module. The normal browser app and dual-engine implementation do not depend on AI internals.

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
