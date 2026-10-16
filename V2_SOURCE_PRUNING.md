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

Third pass (fix CI after removing attributed-metrics implementation):
- Replaced `DuckAiAttributedMetric` with a no-op `DuckAiMetricCollector` binding.
- Replaced `RealAdClickAttributedMetric` with a no-op `AdClickCollector` binding.
- Removed `SubscriptionStatusAttributedMetric`, an obsolete subscription
  retention/attribution lifecycle observer, and all three retired unit tests.
- This removes the Metro dependency on the deleted `AttributedMetricClient`
  and `AttributedMetricConfig` implementations without re-enabling analytics.
- Browser, ad-blocking and Duck AI callback contracts remain intact.
- VPN, PIR, subscription services, DuckChat, sync and remote messaging are
  still present. This is not yet a fully stripped browser.

CI repair (run 38928993968): removed the orphaned `SyncDevicesAttributeMetric` analytics observer
and its unit test. Sync's actual connected-device state observer is retained; the deleted
observer only emitted attributed device-count metrics and required the removed
`AttributedMetricClient` and `AttributedMetricConfig` DI bindings.

Fourth pass (remove internal-only tooling modules; requires new CI validation):
- `duckchat-internal` removed from `app`'s `internalImplementation` and its source directory
- `vpn-internal` removed from `app`'s `internalImplementation` and its source directory
- `pir-internal` removed from `app`'s `internalImplementation` and its source directory
- `subscriptions-internal` removed from `app`'s `internalImplementation` and its source directory
- `sync-internal` removed from `app`'s `internalImplementation` and its source directory
- `remote-messaging-internal` removed from `app`'s `internalImplementation` and its source directory
- `network-protection-internal` removed from `app`'s `internalImplementation` and its source directory

This reduces internal debug-only tools, but does not remove the corresponding product runtime modules. Do not claim VPN/PIR/subscriptions/DuckChat/sync/remote messaging are fully eliminated until their consumer graphs are decoupled and compiled.
