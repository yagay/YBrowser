# Third-party notices

## chatgpt-web-adapter

Parts of YBrowser's ChatGPT Web canonical conversation read policy and browser
transport adaptation are derived from and kept aligned with:

- Project: `kymuco/chatgpt-web-adapter`
- Reference revision: `79064a1df4f5962681c6500f1f76316d1900cadd`
- Upstream copyright: Copyright (c) 2026 Kymuco
- License: MIT

MIT License

Copyright (c) 2026 Kymuco

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE.

### YBrowser adaptation boundary

YBrowser does not embed the upstream Python/Chrome runtime. The Android-specific
GeckoView/WebExtension bridge is YBrowser glue; ChatGPT conversation endpoint,
pagination, fallback and canonical-finality policy follow the upstream contract
recorded in `ai-workspace/UPSTREAM_CHATGPT_PROVIDER.md`.
