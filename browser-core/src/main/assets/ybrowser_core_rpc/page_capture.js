"use strict";

(() => {
  if (globalThis.__YBROWSER_AI_PAGE_CAPTURE__) return;

  const SOURCE_PAGE = "ybrowser-ai-page";
  const SOURCE_EXTENSION = "ybrowser-ai-extension";
  const MAX_BODY_CHARS = 12 * 1024 * 1024;
  const CHUNK_CHARS = 256 * 1024;
  const MAX_CACHE_ITEMS = 3;
  const MAX_CACHE_CHARS = 14 * 1024 * 1024;

  let enabled = false;
  let configuredHints = [];
  let sequence = 0;
  const cache = [];
  const seen = new Set();

  const normalizeUrl = (value) => {
    try {
      if (value instanceof Request) return value.url || "";
      return new URL(String(value || ""), location.href).href;
    } catch (_) {
      return String(value || "");
    }
  };

  const defaultLikely = (url) => {
    const value = String(url || "").toLowerCase();
    if (!value) return false;
    return [
      "/backend-api/conversation",
      "/conversation/",
      "/chat_conversations",
      "/append_message",
      "/api/v0/chat",
      "/api/v0/chat_session",
      "/api/v2/chats",
      "/api/chat",
      "/rest/app-chat/",
      "/_/bardchatui/",
      "/batchexecute",
    ].some((hint) => value.includes(hint));
  };

  const matchesConfigured = (url) =>
    configuredHints.length === 0 ||
    configuredHints.some((hint) => String(url || "").includes(hint));

  const isConversationWrite = (url, method) => {
    if (String(method || "").toUpperCase() !== "POST") return false;
    try {
      const parsed = new URL(String(url || ""), location.href);
      if (parsed.origin !== "https://chatgpt.com") return false;
      const path = parsed.pathname.replace(/\/+$/, "");
      return (
        path.endsWith("/backend-api/f/conversation") ||
        path.endsWith("/backend-api/conversation")
      );
    } catch (_) {
      return false;
    }
  };

  const sha256Hex = async (value) => {
    const bytes = new TextEncoder().encode(String(value || ""));
    const digest = new Uint8Array(
      await crypto.subtle.digest("SHA-256", bytes)
    );
    return Array.from(
      digest,
      (item) => item.toString(16).padStart(2, "0")
    ).join("");
  };

  const requestText = (message) => {
    const parts = Array.isArray(message?.content?.parts)
      ? message.content.parts
      : [];
    return parts
      .filter((part) => typeof part === "string")
      .join("");
  };

  const emitWriteObservation = async (url, method, rawBody) => {
    if (!enabled || !isConversationWrite(url, method)) return;
    if (typeof rawBody !== "string" || !rawBody) return;

    let payload;
    try {
      payload = JSON.parse(rawBody);
    } catch (_) {
      return;
    }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) return;
    if (payload.action !== "next") return;

    const messages = Array.isArray(payload.messages)
      ? payload.messages
      : [];
    const users = messages.filter(
      (message) =>
        message &&
        typeof message === "object" &&
        !Array.isArray(message) &&
        message?.author?.role === "user"
    );
    if (!users.length) return;

    const safeMessages = [];
    let allUserMessagesHaveId = true;
    for (const message of users) {
      const id =
        typeof message.id === "string"
          ? message.id.trim()
          : "";
      if (!id) allUserMessagesHaveId = false;
      safeMessages.push({
        id,
        textSha256: await sha256Hex(requestText(message)),
      });
    }

    const conversationId =
      typeof payload.conversation_id === "string"
        ? payload.conversation_id.trim()
        : "";

    try {
      window.postMessage(
        {
          source: SOURCE_PAGE,
          type: "write-observation",
          payload: {
            url: normalizeUrl(url),
            method: "POST",
            actionNext: true,
            conversationId,
            allUserMessagesHaveId,
            userMessages: safeMessages,
            capturedAt: Date.now(),
          },
        },
        location.origin
      );
    } catch (_) {}
  };

  const observeFetchWrite = (request, init, requestUrl, method) => {
    if (!enabled || !isConversationWrite(requestUrl, method)) return;

    const body = init?.body;
    if (typeof body === "string") {
      void emitWriteObservation(requestUrl, method, body);
      return;
    }

    if (request instanceof Request) {
      try {
        void request
          .clone()
          .text()
          .then((text) =>
            emitWriteObservation(requestUrl, method, text)
          )
          .catch(() => {});
      } catch (_) {}
    }
  };

  const structuralSignature = (body) => {
    const text = String(body || "");
    const head = text.slice(0, 512);
    const tail = text.slice(-256);
    let hash = 2166136261;
    const sample = head + "|" + tail + "|" + text.length;
    for (let i = 0; i < sample.length; i++) {
      hash ^= sample.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };

  const trimCache = () => {
    while (cache.length > MAX_CACHE_ITEMS) cache.shift();
    let total = cache.reduce((sum, item) => sum + item.body.length, 0);
    while (cache.length > 1 && total > MAX_CACHE_CHARS) {
      total -= cache[0].body.length;
      cache.shift();
    }
  };

  const emitCapture = (capture) => {
    if (!enabled || !matchesConfigured(capture.url)) return;
    const body = String(capture.body || "");
    const chunkCount = Math.max(1, Math.ceil(body.length / CHUNK_CHARS));
    for (let chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
      const payload = {
        requestId: capture.requestId,
        url: capture.url,
        method: capture.method,
        statusCode: capture.statusCode,
        contentType: capture.contentType,
        body: body.slice(
          chunkIndex * CHUNK_CHARS,
          (chunkIndex + 1) * CHUNK_CHARS
        ),
        chunkIndex,
        chunkCount,
        stream: false,
        complete: true,
        truncated: capture.truncated,
        capturedAt: capture.capturedAt,
        decodedPageResponse: true,
      };
      try {
        window.postMessage(
          {
            source: SOURCE_PAGE,
            type: "network",
            payload,
          },
          location.origin
        );
      } catch (_) {}
    }
  };

  const remember = (capture) => {
    if (!capture || !capture.url || !capture.body) return;
    const signature =
      capture.url + "|" + capture.statusCode + "|" +
      capture.body.length + "|" + structuralSignature(capture.body);
    if (seen.has(signature)) return;
    seen.add(signature);
    if (seen.size > 40) {
      const keep = Array.from(seen).slice(-20);
      seen.clear();
      keep.forEach((value) => seen.add(value));
    }
    cache.push(capture);
    trimCache();
    emitCapture(capture);
  };

  const captureText = async (response, requestUrl, method) => {
    try {
      if (!response || typeof response.clone !== "function") return;
      const url = response.url || requestUrl || "";
      if (!defaultLikely(url) && !(enabled && matchesConfigured(url))) return;

      const contentType = String(response.headers?.get?.("content-type") || "");
      const textual =
        /json|event-stream|text\/|x-component|javascript/i.test(contentType) ||
        defaultLikely(url);
      if (!textual) return;

      const text = await response.clone().text();
      if (!text) return;
      const truncated = text.length > MAX_BODY_CHARS;
      const body = truncated ? text.slice(0, MAX_BODY_CHARS) : text;

      remember({
        requestId: "page-fetch-" + Date.now() + "-" + (++sequence),
        url,
        method: String(method || "GET").toUpperCase(),
        statusCode: Number(response.status || 0),
        contentType,
        body,
        truncated,
        capturedAt: Date.now(),
      });
    } catch (_) {}
  };

  try {
    const originalFetch = globalThis.fetch;
    if (typeof originalFetch === "function") {
      globalThis.fetch = new Proxy(originalFetch, {
        apply(target, thisArg, args) {
          const request = args && args[0];
          const init = args && args[1];
          const requestUrl = normalizeUrl(request);
          const method =
            (init && init.method) ||
            (request instanceof Request && request.method) ||
            "GET";


          observeFetchWrite(
            request,
            init,
            requestUrl,
            method
          );

          const promise = Reflect.apply(target, thisArg, args);
          Promise.resolve(promise).then(
            (response) => captureText(response, requestUrl, method),
            () => {}
          );
          return promise;
        },
      });
    }
  } catch (_) {}

  try {
    const proto = XMLHttpRequest.prototype;
    const originalOpen = proto.open;
    const originalSend = proto.send;
    const xhrState = new WeakMap();

    proto.open = function(method, url, ...rest) {
      xhrState.set(this, {
        method: String(method || "GET").toUpperCase(),
        url: normalizeUrl(url),
      });
      return originalOpen.call(this, method, url, ...rest);
    };

    proto.send = function(...args) {
      const xhr = this;
      const state = xhrState.get(xhr) || { method: "GET", url: "" };
      const requestBody = args && args.length ? args[0] : null;
      if (typeof requestBody === "string") {
        void emitWriteObservation(
          state.url,
          state.method,
          requestBody
        );
      }
      const onLoadEnd = () => {
        try {
          const url = xhr.responseURL || state.url;
          if (!defaultLikely(url) && !(enabled && matchesConfigured(url))) return;
          if (xhr.responseType && xhr.responseType !== "text") return;
          const text = String(xhr.responseText || "");
          if (!text) return;
          const contentType = String(xhr.getResponseHeader("content-type") || "");
          const truncated = text.length > MAX_BODY_CHARS;
          remember({
            requestId: "page-xhr-" + Date.now() + "-" + (++sequence),
            url,
            method: state.method,
            statusCode: Number(xhr.status || 0),
            contentType,
            body: truncated ? text.slice(0, MAX_BODY_CHARS) : text,
            truncated,
            capturedAt: Date.now(),
          });
        } catch (_) {}
      };
      xhr.addEventListener("loadend", onLoadEnd, { once: true });
      return originalSend.apply(xhr, args);
    };
  } catch (_) {}

  window.addEventListener("message", (event) => {
    if (event.source !== window) return;
    const data = event.data;
    if (!data || data.source !== SOURCE_EXTENSION || data.type !== "configure") {
      return;
    }

    enabled = !!data.enabled;
    configuredHints = Array.isArray(data.urlHints)
      ? data.urlHints.map((value) => String(value || "")).filter(Boolean)
      : [];

    if (enabled) {
      // Passive only: replay captures that the page itself already produced.
      // Never repeat a history request or force ChatGPT to load older content.
      cache.forEach((capture) => emitCapture(capture));
    }
  });

  globalThis.__YBROWSER_AI_PAGE_CAPTURE__ = {
    version: 2,
    get cachedCount() { return cache.length; },
  };
})();
