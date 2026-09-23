# ChatGPT Web Provider upstream contract

YBrowser does **not** define an independent ChatGPT Web protocol.

## Authoritative upstream

- Project: `kymuco/chatgpt-web-adapter`
- License: MIT
- Reference revision: `79064a1df4f5962681c6500f1f76316d1900cadd`
- Reference date: 2026-09-22
- Primary surfaces followed by YBrowser:
  - PR13.0 canonical conversation read v2
  - browser-owned product transport
  - revision-safe streaming / canonical finality separation

Before changing ChatGPT wire behavior in YBrowser, inspect the current upstream
implementation and update this reference revision when the adopted behavior
changes.

## Adopted protocol rules

### Canonical conversation read

YBrowser follows the CWA PR13.0 policy:

1. Use the authenticated browser-owned ChatGPT session.
2. Read the current endpoint first:
   `GET /backend-api/conversations/{id}?include_has_versions=true&num_turns=20`
3. Treat `num_turns=20` as a server hint, not a completeness guarantee.
4. Follow `page_info.has_previous_page` with
   `before=page_info.start_cursor` when full history is requested.
5. Bound pagination and reject repeated cursors / identity disagreement.
6. Retry bounded HTTP 429 responses with capped backoff.
7. Fall back to the legacy singular endpoint only when the current endpoint
   returns HTTP 404.
8. Do not hide authentication, challenge, malformed response, or server errors
   behind a different transport.
9. Normalize the current flat `messages[]` branch into one deterministic
   canonical mapping before it enters the Android conversation store.

### Streaming

Streaming is provisional observation only.

- Passive response/SSE capture may update the visible assistant text early.
- A streamed snapshot or quiet network period is **not** canonical finality.
- After a ChatGPT write, YBrowser performs one bounded canonical read and lets
  that read reconcile/finalize the stored conversation.
- No automatic repeat of an ambiguous product write is allowed.

### UI / project tabs

YBrowser-specific behavior is outside the upstream wire contract:

- one project tab may aggregate multiple ChatGPT conversation URLs;
- ConversationStore owns the merged local project history;
- the currently bound URL selects the active remote conversation;
- GeckoView/WebExtension is the browser transport instead of CWA's
  Chrome/Native-Messaging implementation.

These differences must stay in the glue layer and must not create a second
ChatGPT protocol implementation.

## Files that form the YBrowser glue layer

- `browser-core/src/main/assets/ybrowser_core_rpc/chatgpt_cwa_canonical_read.js`
- `browser-core/src/main/assets/ybrowser_core_rpc/content.js`
- `ai-workspace/src/main/java/com/yagay/ybrowser/ai/web/GeckoProviderRuntime.kt`
- `ai-workspace/src/main/java/com/yagay/ybrowser/ai/web/provider/ChatGptWebProviderAdapter.kt`
- `ai-workspace/src/main/java/com/yagay/ybrowser/ai/ui/WorkspaceViewModel.kt`

## Do not reintroduce

Do not add any of the following as a normal ChatGPT path unless the adopted
upstream architecture changes first:

- a separate Page API broker session;
- account/workspace enumeration solely to make a private endpoint work;
- DOM transcript scraping as canonical history or finality;
- a second independently maintained ChatGPT history endpoint implementation;
- silent transport fallback after non-404 canonical-read failures;
- automatic write retry after an ambiguous delegated send.

Diagnostics may observe additional evidence, but observation must not become a
second authority path.
