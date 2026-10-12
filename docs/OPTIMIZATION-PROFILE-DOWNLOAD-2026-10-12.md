# YBrowser profile switching and download consistency audit — 2026-10-12

Baseline: `1b0f5f1c23956821e12ddfa26ebe2b0950a24393`, all unit tests and ARM64 Debug passed.

## Issues corrected

- **UI-thread Profile switch:** `BrowserApp.switchProfile` previously serialized outgoing tabs and parsed target tabs/bookmarks/history on the UI thread. It now shows an in-progress modal, serializes its outgoing snapshot with normal autosaves, and loads the target snapshot on `Dispatchers.IO`. The old renderer is not closed until target data is available; failures leave the old Profile selected. Completion still applies Compose/native-engine state on the UI thread.
- **Redundant Profile parse:** `initialSession` remembered a new parsed initial session every time the effective Profile changed, even though the existing tabs state does not consume that parsed object after initial creation. It no longer re-parses on Profile switches.
- **Stale async updates:** bookmark imports, history loads, history saves and history-clear operations compared against a captured Profile value, not the latest selected Profile. Completion now checks a `rememberUpdatedState` reference to avoid surfacing old Profile data in a newly selected Profile.
- **Profile ID collisions:** Profile IDs were generated from `System.currentTimeMillis()`. Creation now allocates collision-checked UUID-backed safe storage names. Create, rename and removal operations are synchronized and removing a missing Profile no longer incorrectly returns success.
- **Download-record races:** system enqueue and record deletion/cleanup/retry use the same synchronization as native progress updates, protecting read-modify-write sequences against lost records. Native progress updates now skip writes when a task has already been removed or its persisted fields did not change.
- **Tests:** pure Kotlin `BrowserProfileIdPolicyTest` checks safe IDs, collision retries and exhaustion.

## Scope / remaining validation

CI: `:ai-workspace:testDebugUnitTest`, `:app:testDebugUnitTest`, ARM64 `assembleDebug`. On-device tests remain needed for live Gecko/WebView Profile isolation, navigation during switching, cookie separation, DownloadManager behavior, and retained Surface/IME focus. Android CI passing does not prove those runtime scenarios.
