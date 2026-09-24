# ChatGPT provider: upstream CWA mapping

YBrowser's ChatGPT provider follows the production architecture and safety/finality
invariants of [kymuco/chatgpt-web-adapter](https://github.com/kymuco/chatgpt-web-adapter).

Reviewed upstream:
- public release: v0.3.0
- main: `1d449bc22614c5bc27f1e1cb4bfaeed3794d5921`
- license: MIT

YBrowser does not embed CWA's Python client, Chrome Native Messaging host or CDP
implementation. Android keeps GeckoView as the browser-owned product runtime and
maps each upstream responsibility to one Android owner.

## Production invariants

These are hard provider contracts, not UI conventions:

- product `conversation_id` is conversation identity authority;
- the SPA route is navigation state, not ordinary-write identity authority;
- a product write is page-owned and is attempted at most once;
- an ambiguous delegated write never grants automatic retry authority;
- network/SSE/DOM observations are provisional;
- only canonical conversation readback grants canonical history/finality;
- canonical read timeout recovery may repeat the read, never the write;
- local persisted history is display/cache state and never replay authority.

## Upstream -> Android/Gecko mapping

| CWA production responsibility | YBrowser Android owner |
| --- | --- |
| browser-owned runtime tab | per-window `GeckoCoreSession` |
| retained conversation tab state | `AiTabCacheStore` + Gecko `SessionState` + bounded hot-session pool |
| Native Messaging transport | built-in Gecko WebExtension port / `GeckoRpcBridge` |
| runtime/page script execution | async WebExtension RPC |
| protected page submit | provider page runtime (`common.js` / attachment picker), one write only |
| request-bound write observation | main-world fetch/XHR safe write observation + Native correlation |
| active realtime response stream | Gecko WebRequest `filterResponseData` + `ChatGptActiveStreamProvider` |
| canonical read v2 | `chatgpt-cwa-canonical.js` + `ChatGptProductProvider` |
| canonical pagination | 20-turn current endpoint pages, cursor walk, max 100 pages, 75 ms pacing |
| canonical throttle recovery | Retry-After / bounded exponential backoff |
| canonical read timeout recovery | at most 2 fresh reads, 250 ms delay |
| canonical large-result transfer | chunked UTF-8 RPC frames + total byte count + SHA-256 verification |
| canonical payload validation | conversation identity + stable message-id validation, fail closed |
| final local history | page/conversation-scoped `ConversationStore` |

Android intentionally does not keep CWA's desktop Chrome tab count resident. Bound
conversations retain persistent SessionState and local history, while Gecko uses a
small hot-session budget and restores frozen sessions on demand. This preserves the
runtime identity/lifecycle contract without treating mobile memory like desktop Chrome.

## Read path

```text
local ConversationStore
    -> render immediately
    -> active SSE stream for live turns
       -> product conversation_id routes the turn
       -> UI memory-first update
       -> debounced local persistence
    -> stream terminal event
       -> latest-page canonical read
       -> canonical reconciliation/finality
    -> full canonical pagination only when:
       -> local cache is empty
       -> user explicitly refreshes history
       -> recovery requires a full rebuild
    -> canonical read v2
       -> current /backend-api/conversations/<id>
       -> cursor pagination when requested
       -> page/conversation identity checks
       -> stable message-id de-duplication
       -> bounded read-only timeout retry
    -> chunked RPC transfer
       -> manifest/count/byte validation
       -> SHA-256 verification
    -> canonical provider parsing
    -> reconcile and persist
```

The AI workspace is browser-first. A project tag owns a retained live Gecko page/session,
and that provider page is the only visible conversation source. The native shell owns
project tabs, binding metadata, navigation, diagnostics and browser controls, but it does
not reconstruct the conversation body into a second Native Chat transcript. Existing
SQLite/native transcript data is retained for compatibility and migration only; it is not
the presentation authority.

For ChatGPT, realtime assistant text is owned by one path only:

```text
ChatGPT POST /backend-api/(f/)?conversation
    -> Gecko WebRequest filterResponseData
    -> complete SSE events, coalesced at ~40 ms
    -> ChatGptActiveStreamProvider
       -> stream_handoff conversation_id
       -> final/all visible assistant only
       -> p/o/v patch state machine
       -> bare v append support
       -> revision-safe full message snapshots
       -> message_stream_complete / end_turn
    -> in-memory/Compose merge immediately
    -> SQLite persistence debounced ~150 ms
       (terminal frame persists immediately)
```

The page-level fetch/XHR clone path is not a second ChatGPT POST message authority.
It remains useful for history/diagnostics and non-ChatGPT providers, but ChatGPT live
POST responses are skipped once the active stream owns them. DOM observation is not
part of the normal ChatGPT message path.

## Write path

```text
one local optimistic message
    -> one page-owned submit
    -> safe request observation
       -> action=next
       -> product conversation id
       -> logical user message id
       -> SHA-256(exact submitted text)
    -> exact request correlation when available
    -> provisional stream observation
    -> canonical reconciliation/finality
```

Send outcomes are explicit:

- `CONFIRMED`: request/product evidence confirms the delegated write.
- `AMBIGUOUS`: evidence indicates the write may have been submitted but exact
  confirmation is incomplete. Keep the local turn, continue observation/canonical
  reconciliation, and never automatically resend.
- `FAILED`: no evidence crossed the product write boundary; the optimistic turn may
  be reverted.

## Conversation identity and project tags

Project identity owns the UI tag. Product conversation identity owns the chat history.

```text
project tag
    -> current bound canonical conversation_id
       -> persisted history for that conversation
```

A route transition such as `/c/WEB:<temporary>` is not allowed to mint durable
identity. Once the product/network/canonical plane proves the real
`conversation_id`, the binding and local history move to that canonical identity.

An explicit rebind to a different real conversation changes the page-history owner.
It does not merge unrelated conversations into one project history.

## What is not copied literally

The following CWA implementation mechanisms are platform-specific and are replaced by
Android equivalents rather than duplicated:

- Python client/facade and local socket descriptor;
- Chrome `chrome.debugger` / CDP attachment;
- Chrome tab APIs and desktop tab pool sizing;
- browserless experimental transport;
- research/characterization wrappers whose behavior is not part of the Android
  product surface.

Temporary-chat characterization, connector execution authority and model-profile
selection are not implicitly claimed merely because upstream contains those research
or capability modules. They should be exposed only when YBrowser has an Android
product implementation with equivalent evidence.

## Maintenance rule

Provider semantics belong below the UI. New ChatGPT wire behavior should be adapted in
the provider/runtime boundary rather than patched into project/tag/history UI code.

When updating CWA:
1. review current production owners and invariants;
2. update the pinned upstream commit;
3. adapt Chrome-specific mechanics to Gecko rather than copying them blindly;
4. keep write retry disabled;
5. add deterministic tests for identity/finality changes;
6. require AI workspace tests and APK build to pass before treating the update as
   integrated.


## Active realtime provider

ChatGPT realtime delivery is browser-owned and active. Gecko's WebExtension
`webRequest.filterResponseData()` observes the official page's conversation POST
response bytes as they arrive. Complete SSE records are forwarded to
`ChatGptActiveStreamProvider`, which owns provisional live display.

The active provider:
- adopts product `conversation_id` from `stream_handoff`/stream metadata;
- accepts legacy full-message envelopes and current `p/o/v` patches;
- accepts bare string `v` appends that inherit the current message target;
- supports server revisions where a later full snapshot replaces earlier text;
- exports only visible assistant content addressed to `all`;
- excludes commentary/reasoning/tool targets from the normal transcript;
- recognizes `message_stream_complete`, `end_turn`, and `[DONE]`;
- never claims canonical finality.

Live snapshots update Compose memory before SQLite I/O. Persistence is debounced for
ongoing text and immediate for a terminal stream snapshot. A terminal stream then
triggers one latest-page canonical reconciliation. Full paginated canonical history is
reserved for empty-cache bootstrap, explicit refresh, or recovery.

This gives ChatGPT one realtime message owner and one finality owner:

```text
realtime owner:  ChatGptActiveStreamProvider
finality owner:  CanonicalConversationClient / canonical-read v2
```

Page-level POST response cloning and DOM scraping are not parallel ChatGPT realtime
message authorities.
