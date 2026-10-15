# YBrowser V2 — DuckDuckGo-based upstream import

Original project: https://github.com/duckduckgo/Android (develop)
Imported commit: bfbf8432dfaf018530e623e4fb84b17326c66848

This branch is based on the complete DuckDuckGo Android source tree,
including the original module graph and Git submodules. Original
LICENSE, NOTICE, and per-file copyright notices remain intact.
No proprietary Duck Sans font has been copied or licensed for use.
The default build must use -PuseProprietaryFont=false.

YBrowser changes at this stage:
- Changed app applicationId to com.yagay.ybrowser.v2.
- Changed main application label to YBrowser V2.
- Replaced upstream private/release CI workflows with independent
  ARM64 debug CI for this branch.

The source import is NOT proof of feature parity: DuckDuckGo online
services, third-party permissions, privacy data and hard-coded
application identities must be audited and tested individually.
Submodules must be initialized on checkout; npm resources are required.
