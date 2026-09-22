"use strict";

const captureConfigs = new Map();
const requestMeta = new Map();

const MAX_CAPTURE_CHARS = 8 * 1024 * 1024;
const TRANSPORT_CHUNK_CHARS = 384 * 1024;
const STREAM_EMIT_LIMIT = 256 * 1024;

function safeSend(tabId, payload) {
  if (tabId == null || tabId < 0) return;
  try {
    const result = browser.tabs.sendMessage(tabId, {
      type: "ai-network",
      payload,
    });
    if (result && typeof result.catch === "function") {
      result.catch(() => {});
    }
  } catch (_) {}
}

function sendChunked(meta, body, truncated) {
  const value = String(body || "");
  const count = Math.max(1, Math.ceil(value.length / TRANSPORT_CHUNK_CHARS));
  for (let index = 0; index < count; index++) {
    safeSend(meta.tabId, {
      requestId: meta.requestId,
      url: meta.url,
      method: meta.method,
      statusCode: meta.statusCode || 0,
      contentType: meta.contentType || "",
      body: value.slice(
        index * TRANSPORT_CHUNK_CHARS,
        (index + 1) * TRANSPORT_CHUNK_CHARS
      ),
      chunkIndex: index,
      chunkCount: count,
      stream: false,
      complete: true,
      truncated: !!truncated,
      capturedAt: Date.now(),
    });
  }
}

function flushStream(meta) {
  if (meta.streamTimer !== null) {
    clearTimeout(meta.streamTimer);
    meta.streamTimer = null;
  }
  const value = String(meta.streamQueued || "").trim();
  meta.streamQueued = "";
  if (!value || value.length > STREAM_EMIT_LIMIT) return;
  safeSend(meta.tabId, {
    requestId: meta.requestId,
    url: meta.url,
    method: meta.method,
    statusCode: meta.statusCode || 0,
    contentType: meta.contentType || "",
    body: value,
    chunkIndex: 0,
    chunkCount: 1,
    stream: true,
    complete: false,
    truncated: false,
    capturedAt: Date.now(),
  });
}

function emitStreamBlock(meta, block) {
  const value = String(block || "").trim();
  if (!value) return;
  meta.streamQueued += (meta.streamQueued ? "\n\n" : "") + value;
  if (meta.streamQueued.length >= STREAM_EMIT_LIMIT / 2) {
    flushStream(meta);
    return;
  }
  if (meta.streamTimer === null) {
    meta.streamTimer = setTimeout(() => flushStream(meta), 250);
  }
}

browser.runtime.onMessage.addListener((message, sender) => {
  if (!message || !sender || !sender.tab || sender.tab.id == null) return;
  const tabId = sender.tab.id;
  if (message.type === "ai-capture-enable") {
    const hints = Array.isArray(message.urlHints)
      ? message.urlHints.map((value) => String(value || "").trim()).filter(Boolean)
      : [];
    captureConfigs.set(tabId, { hints });
    return Promise.resolve({ ok: true, tabId, hintCount: hints.length });
  }
  if (message.type === "ai-capture-disable") {
    captureConfigs.delete(tabId);
    return Promise.resolve({ ok: true, tabId });
  }
});

if (browser.tabs && browser.tabs.onRemoved) {
  browser.tabs.onRemoved.addListener((tabId) => {
    captureConfigs.delete(tabId);
    for (const [requestId, meta] of requestMeta.entries()) {
      if (meta.tabId === tabId) requestMeta.delete(requestId);
    }
  });
}

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    const config = captureConfigs.get(details.tabId);
    if (!config) return;
    if (
      config.hints.length > 0 &&
      !config.hints.some((hint) => String(details.url || "").includes(hint))
    ) {
      return;
    }

    let filter;
    try {
      filter = browser.webRequest.filterResponseData(details.requestId);
    } catch (_) {
      return;
    }

    const meta = {
      requestId: details.requestId,
      tabId: details.tabId,
      url: details.url || "",
      method: details.method || "GET",
      statusCode: 0,
      contentType: "",
      eventStream: false,
      decoder: new TextDecoder("utf-8"),
      parts: [],
      chars: 0,
      truncated: false,
      streamPending: "",
      streamQueued: "",
      streamTimer: null,
    };
    requestMeta.set(details.requestId, meta);

    filter.ondata = (event) => {
      try {
        const text = meta.decoder.decode(event.data, { stream: true });

        if (!meta.truncated) {
          if (meta.chars + text.length <= MAX_CAPTURE_CHARS) {
            meta.parts.push(text);
            meta.chars += text.length;
          } else {
            const remaining = Math.max(0, MAX_CAPTURE_CHARS - meta.chars);
            if (remaining > 0) {
              meta.parts.push(text.slice(0, remaining));
              meta.chars += remaining;
            }
            meta.truncated = true;
          }
        }

        if (meta.eventStream && text) {
          meta.streamPending += text;
          let boundary;
          while ((boundary = meta.streamPending.indexOf("\n\n")) >= 0) {
            const block = meta.streamPending.slice(0, boundary);
            meta.streamPending = meta.streamPending.slice(boundary + 2);
            emitStreamBlock(meta, block);
          }
        }
      } catch (_) {}

      try {
        filter.write(event.data);
      } catch (_) {}
    };

    filter.onstop = () => {
      try {
        const tail = meta.decoder.decode();
        if (tail && !meta.truncated) {
          if (meta.chars + tail.length <= MAX_CAPTURE_CHARS) {
            meta.parts.push(tail);
            meta.chars += tail.length;
          } else {
            const remaining = Math.max(0, MAX_CAPTURE_CHARS - meta.chars);
            if (remaining > 0) meta.parts.push(tail.slice(0, remaining));
            meta.truncated = true;
          }
        }

        if (meta.eventStream && meta.streamPending.trim()) {
          emitStreamBlock(meta, meta.streamPending);
        }
        flushStream(meta);

        const body = meta.parts.join("");
        const textual =
          /json|event-stream|text\//i.test(meta.contentType || "") ||
          /^\s*(\{|\[|data:)/.test(body);
        if (textual) {
          sendChunked(meta, body, meta.truncated);
        }
      } catch (_) {
      } finally {
        requestMeta.delete(details.requestId);
        try { filter.close(); } catch (_) {}
      }
    };

    filter.onerror = () => {
      if (meta.streamTimer !== null) clearTimeout(meta.streamTimer);
      requestMeta.delete(details.requestId);
      try { filter.disconnect(); } catch (_) {}
    };
  },
  { urls: ["<all_urls>"], types: ["xmlhttprequest"] },
  ["blocking"]
);

browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    const meta = requestMeta.get(details.requestId);
    if (!meta) return;

    meta.statusCode = details.statusCode || 0;
    const headers = details.responseHeaders || [];
    const contentType = headers.find(
      (header) => String(header.name || "").toLowerCase() === "content-type"
    );
    meta.contentType = String(contentType && contentType.value || "");
    meta.eventStream = /text\/event-stream/i.test(meta.contentType);
  },
  { urls: ["<all_urls>"], types: ["xmlhttprequest"] },
  ["responseHeaders"]
);
