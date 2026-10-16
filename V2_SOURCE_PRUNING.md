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

Second pass (vendor runtime implementation removal, pending CI):
- `dax-prompts/dax-prompts-api` (no remaining implementation; old welcome prompt API)
- `referral/referral-impl` (install/referral marketing implementation)
- `attributed-metrics/attributed-metrics-impl`, `attributed-metrics/attributed-metrics-internal` (origin-attribution telemetry implementation)

The `referral-api` and `attributed-metrics-api` contracts remain because application and other modules still compile against them. VPN, PIR, subscriptions, DuckChat, sync, and remote messaging remain and are not claimed removed. CI success only confirms a build; Android feature testing remains necessary.

CI repair: removed the obsolete internal-only StatisticsAttributedMetricsPlugin, StatisticsInternalInfoView, and their unused layout after deleting the attributed-metrics-internal implementation. These were the two Kotlin unresolved references reported by the first V2 deletion CI run (38925122402).

CI repair (run 38926259686): restored the AppReferrer compile-time DI binding with an application-local NoOpAppReferrer. This deliberately does not restore Play Store referral attribution or persist campaign identifiers.
