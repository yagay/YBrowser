# YBrowser source optimization — 2026-10-11

Baseline: `be1e36c8e6f1c28199bfa157ea1822f2bf0b9d16`; previous Debug ARM64 and browser/AI tests passed.

## Findings and corrections

1. **Gecko saved state resilience:** `browser-core/GeckoCoreSession` parsed and restored untrusted persisted state without an exception boundary. Invalid/obsolete snapshots could abort core Gecko session initialization. Parsing and restore now fail safely to the last known URL while preserving the RPC-gated loading mode.
2. **Private session isolation:** even private GeckoCore sessions previously serialized their navigation state via `onSessionState`. The core now ignores persisted restoration for private sessions and never emits private session snapshots.
3. **Queued Gecko snapshot cleanup:** when profile deletion was scheduled, a late session callback could enqueue another saved state while cleanup was underway. The tab state store rejects new writes for clearing profiles and checks deletion/profile tombstones before returning pending data.
4. **Shared upload stager:** browser and AI workspace previously maintained two nearly identical 1GiB ContentResolver copy loops with separate limits, file-name sanitizers and cleanup behavior. Both now use `GeckoCoreUploadStager`, leaving browser-specific count-only diagnostics in a thin wrapper. Avoid writing sensitive upload URIs or absolute staged paths to logs.
5. **No upload copying on the UI thread:** Gecko file prompt completion no longer copies up to 1GiB before returning. The core stages content in a fixed two-thread worker pool and reports results on the main looper; staged-file cleanup is queued after outstanding copy work.
6. **Bounded favicon miss cache:** `BrowserFaviconRepository` no longer remembers failed websites indefinitely. Transient failures expire in fifteen minutes, with only 128 misses retained. HTTPS host normalization also supports bracketed IPv6 literals.
7. **Tests:** new pure Kotlin `TimedNegativeCacheTest` exercises TTL expiry, eviction and overflow handling.

## Validation

Check `:ai-workspace:testDebugUnitTest`, `:app:testDebugUnitTest`, and ARM64 `assembleDebug` on the final commit. The Gecko upload UI interaction and Surface/input lifecycles additionally require Android device testing. Successful CI is not proof of runtime correctness.
