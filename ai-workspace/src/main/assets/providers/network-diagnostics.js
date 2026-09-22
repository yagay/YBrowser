(() => {
  const state = window.__AIHUB_NET_DIAG__ || (window.__AIHUB_NET_DIAG__ = {
    installed: false,
    events: []
  });
  if (state.installed) return;
  state.installed = true;

  const now = () => Date.now();
  const redactPath = (raw) => {
    try {
      const url = new URL(String(raw || ""), location.href);
      const parts = url.pathname.split("/").map((segment) => {
        if (segment.length >= 16 && /^[A-Za-z0-9_-]+$/.test(segment)) return "<id>";
        if (segment.length >= 16 && /^[0-9a-f-]+$/i.test(segment)) return "<id>";
        return segment;
      });
      return `${url.host}${parts.join("/")}`.slice(0, 260);
    } catch (_) {
      return "<invalid>";
    }
  };

  const bodySize = (body) => {
    if (body == null) return 0;
    if (typeof body === "string") return body.length;
    if (body instanceof Blob) return body.size || 0;
    if (body instanceof ArrayBuffer) return body.byteLength || 0;
    if (ArrayBuffer.isView(body)) return body.byteLength || 0;
    if (body instanceof URLSearchParams) return String(body).length;
    return -1;
  };

  const push = (event) => {
    state.events.push(event);
    if (state.events.length > 60) state.events.splice(0, state.events.length - 60);
  };

  const originalFetch = window.fetch;
  if (typeof originalFetch === "function") {
    window.fetch = function(input, init) {
      const started = now();
      const method = String(init?.method || input?.method || "GET").toUpperCase().slice(0, 12);
      const target = redactPath(input?.url || input);
      const requestBytes = bodySize(init?.body);
      return originalFetch.apply(this, arguments).then((response) => {
        push({
          kind: "fetch",
          method,
          target,
          status: Number(response?.status || 0),
          ok: !!response?.ok,
          durationMs: now() - started,
          requestBytes
        });
        return response;
      }).catch((error) => {
        push({
          kind: "fetch",
          method,
          target,
          status: 0,
          ok: false,
          durationMs: now() - started,
          requestBytes,
          error: String(error?.name || "fetch-error").slice(0, 80)
        });
        throw error;
      });
    };
  }

  const XHR = window.XMLHttpRequest;
  if (XHR?.prototype) {
    const originalOpen = XHR.prototype.open;
    const originalSend = XHR.prototype.send;
    XHR.prototype.open = function(method, url) {
      this.__aihubDiag = {
        method: String(method || "GET").toUpperCase().slice(0, 12),
        target: redactPath(url),
        started: 0,
        requestBytes: 0
      };
      return originalOpen.apply(this, arguments);
    };
    XHR.prototype.send = function(body) {
      const meta = this.__aihubDiag || (this.__aihubDiag = {});
      meta.started = now();
      meta.requestBytes = bodySize(body);
      const done = () => {
        if (meta.recorded) return;
        meta.recorded = true;
        push({
          kind: "xhr",
          method: meta.method || "GET",
          target: meta.target || "<unknown>",
          status: Number(this.status || 0),
          ok: Number(this.status || 0) >= 200 && Number(this.status || 0) < 400,
          durationMs: meta.started ? now() - meta.started : -1,
          requestBytes: meta.requestBytes ?? -1
        });
      };
      this.addEventListener("loadend", done, { once: true });
      return originalSend.apply(this, arguments);
    };
  }
})();
