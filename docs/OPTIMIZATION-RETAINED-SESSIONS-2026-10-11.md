# YBrowser retained-session correctness pass — 2026-10-11

Baseline: `6251dfdc22b6420ee68168bbf09be7fbc1e2791e` (previous CI passed).

## Fixed

- **Stale renderer callbacks:** `TabSessionManager` now assigns each engine instance a callback-generation token. Late callbacks from replaced/destroyed engines cannot update the currently selected page, and queued capture callbacks cannot install previews for a renderer no longer associated with that tab.
- **Preserved Gecko state:** A session evicted for memory pressure can still flush its navigation snapshot, but a new engine generation invalidates the previous writer. Callbacks from an evicted renderer no longer emit stale UI page states.
- **Session state read-after-write:** `BrowserTabSessionStateStore.read()` prefers the latest pending snapshot during its 800ms debounce/write window; deletion and whole-profile removal expose temporary tombstones so stale disk files cannot be restored while cleanup is queued.
- **Avoid cancelled timer build-up:** the session state writer removes cancelled debounce jobs from its executor queue immediately.
- **Correct cleanup after cold eviction:** closing a tab provides its effective profile even when there is no resident engine, so persisted Gecko state is removed. Changing engine or profile invalidates the previous saved Gecko back-forward stack.
- **Safer renderer shutdown:** exceptions in an old engine's flush/destroy no longer stop replacement or leave later cleanup undone. Engine configurations are committed only after successfully applying them.

## Tests and acceptance

New `TabCallbackOwnershipTest` covers generation replacement, eviction with deferred flush, and revoke-on-close/destroy. The pure Kotlin callback token logic was independently compiled and smoke-tested.

CI must run the browser and AI workspace unit tests and ARM64 Debug APK build on the final commit. Device acceptance remains necessary for WebView/GeckoView rendering, keyboard/Surface focus, eviction/reopen and private tabs. No claims of end-to-end device coverage are made here.
