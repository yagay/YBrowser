# YBrowser V2 source pruning

Removed after examining the Gradle project dependency graph in `browser-v2-rebuild`:
- `macos/`, `windows/` (desktop vendor integrations)
- `dax-prompts/` (legacy vendor prompts)
- `feedback/feedback-impl/` (feedback UI implementation)
- `desktop-app-promotion/desktop-app-promotion-impl/` (desktop promotion UI)
- `survey/survey-impl/` (survey UI implementation)

Shared API modules retained for active compile-time contracts:
- `feedback-api` (DuckChat, VPN, privacy dashboard)
- `desktop-app-promotion-api` (sync implementation)
- `survey-api` (app, DuckChat, network protection, subscription)

Not yet removed: DuckChat, subscriptions, VPN, PIR, sync and remote messaging.
Those are still referenced by active runtime modules; removing their Gradle projects
without replacing the dependent workflows would cause an invalid build.

CI: `.github/workflows/build.yml` builds the ARM64 internal debug variant on push.
