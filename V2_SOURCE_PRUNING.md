# YBrowser V2 source pruning

Physical source/module removal:
- `macos/macos-impl`, `windows/windows-impl` (desktop platform implementations)
- `dax-prompts/dax-prompts-impl` (legacy vendor prompt implementation)
- `feedback/feedback-impl` (vendor feedback implementation)
- `desktop-app-promotion/desktop-app-promotion-impl` (desktop promotion implementation)
- `survey/survey-impl` (survey implementation)

Keep these API-only modules as compile-time contracts for referenced code:
- `macos-api`, `windows-api` (subscriptions-impl)
- `dax-prompts-api` (kept conservatively pending direct API consumer audit)
- `feedback-api`, `desktop-app-promotion-api`, `survey-api` (active module dependencies)

Still active and not deleted: DuckChat, subscriptions, VPN, PIR, sync and remote messaging.
These have cross-module dependencies. Removing their implementations requires untangling
call sites and DI providers first, not simply deleting their directories.

CI is configured in `.github/workflows/build.yml` to run on branch pushes.
