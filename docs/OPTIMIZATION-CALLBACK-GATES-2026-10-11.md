# YBrowser renderer callback and privacy audit — 2026-10-11

Baseline: `6a0c9b26c913c46f7e14b9dbdbfd6c1fd4ed0382`; preceding GitHub Actions ARM64 debug and unit tests passed.

## Confirmed defects fixed in this batch

1. **Missing callback forwarders:** `TabSessionManager.CallbackRelay` omitted `onLoginSavePrompt`, `onLoginSelectPrompt`, `onExternalNavigation` and `onHttpsUpgradeFailed`. The missing hooks silently fell back to `BrowserHostCallbacks` defaults, dismissing password prompts and opening external links without the intended browser confirmation. The relay now implements all 18 callback fields.
2. **Dead/background renderer UI calls:** Native Gecko/WebView callbacks may arrive after an engine is evicted/replaced or after the Activity detaches. Every forwarded host callback now checks the renderer generation, resident tab ownership and attached host. Stale prompts are completed with a safe dismissal/denial instead of being routed to the old Activity. Relays detach their delegate upon close/replacement/destruction, releasing captured Compose/Activity references.
3. **Native session identity:** A renderer cannot switch profile or private mode via `applyConfig`. `acquire()` now only reuses the engine when kind, profile and private mode all match. Incompatible native sessions are destroyed and old persisted navigation state is invalidated.
4. **Deleted profile resurrection:** The shared Gecko state writer previously removed its profile tombstone as soon as directory deletion completed, allowing a late obsolete callback to recreate files for the removed profile. A process-lifetime tombstone now rejects later saved-state writes/reads for that deleted profile UUID.
5. **UTF-8 length enforcement:** Gecko session persistence now validates encoded bytes, not Kotlin character count, before accepting pending snapshots.

## Tests

Extended the pure Kotlin `TabCallbackOwnershipTest` to verify that only attached resident generations may request UI, that evicted sessions may retain late state flushes without prompting, and that a superseded renderer cannot act.

## Acceptance

Run `:ai-workspace:testDebugUnitTest`, `:app:testDebugUnitTest`, and ARM64 `assembleDebug` on this exact GitHub commit. Native GeckoView/WebView prompt, background playback, IME and Surface/insets behavior still need Android-device testing.
