# Provider upstream

YBrowser's ChatGPT product-provider boundary follows
[kymuco/chatgpt-web-adapter](https://github.com/kymuco/chatgpt-web-adapter) (CWA).

Pinned compatibility baseline:

- public release: `v0.3.0`
- upstream main reviewed at:
  `83a99e79817db2bba656944e0eedcfe1c661929c`
- upstream license: MIT

## Boundary

YBrowser does **not** embed CWA's Python + Chrome runtime. Android keeps GeckoView as
its browser-owned transport, while matching CWA's application-facing authority
split:

```text
AI UI / project history
        |
        v
ChatGptProductProvider
   /             \
canonical read   product write
   |                 |
   |          Gecko page-owned send
   +--------+--------+
            |
            v
         ChatGPT
```

The important invariants are:

- incremental DOM/SSE/network observation is provisional, not canonical finality;
- the official ChatGPT page owns protected writes;
- one user send causes one write attempt;
- ambiguous write state is reconciled instead of automatically retried;
- no hidden alternate write transport;
- durable response completion is confirmed by canonical conversation readback.

## Files that track the upstream contract

- `web/provider/ProductRuntimeContract.kt` — pinned CWA contract/invariants.
- `web/provider/ChatGptProductProvider.kt` — application-facing ChatGPT provider.
- `web/provider/ChatGptWebProviderAdapter.kt` — low-level compatibility wire decoder only.
- `assets/providers/chatgpt-cwa-canonical.js` — CWA-aligned browser-owned canonical read plane.
- `GeckoProviderRuntime.kt` — Android/Gecko transport adapter.
- `ResponseCompletionPolicy.kt` — finality gate.
- `ProductRuntimeContractTest.kt` — upstream-contract regression tests.

## Updating

When CWA changes:

1. Review its current architecture/contract and canonical-read implementation.
2. Update the pinned commit in `CwaUpstream`.
3. Sync only the transport-facing canonical/read behavior that applies to Gecko.
4. Do not move browser internals into ViewModel/UI code.
5. Keep network/SSE parsing provisional.
6. Keep automatic write retry disabled.
7. Run the full AI workspace tests and debug APK build.

This keeps YBrowser from maintaining an independent ChatGPT product protocol across
the application. Only the Gecko-specific adapter and compatibility decoder remain
local.
