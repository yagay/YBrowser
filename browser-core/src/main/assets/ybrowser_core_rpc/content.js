"use strict";

const NATIVE_APP = "com.yagay.browsercore.rpc";
let port = null;
let reconnectTimer = null;
let networkCaptureEnabled = false;
let networkCaptureHints = [];
let canonicalSequence = 0;
const pendingCanonicalReads = new Map();

function scheduleReconnect() {
  if (reconnectTimer !== null) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, 400);
}

async function runRpc(code) {
  const fn = Function(String(code || ""));
  return await fn();
}

function emitEvent(event, payload) {
  try {
    const encoded =
      typeof payload === "string"
        ? payload
        : JSON.stringify(payload === undefined ? null : payload);
    port?.postMessage({
      type: "rpc-event",
      event: String(event || ""),
      payload: encoded,
      url: location.href,
    });
  } catch (_) {
    scheduleReconnect();
  }
}

globalThis.__YBROWSER_RPC_EMIT__ = emitEvent;

async function setNetworkCapture(enabled, urlHints) {
  networkCaptureEnabled = !!enabled;
  networkCaptureHints = Array.isArray(urlHints)
    ? urlHints.map((value) => String(value || "")).filter(Boolean)
    : [];

  try {
    window.postMessage(
      {
        source: "ybrowser-ai-extension",
        type: "configure",
        enabled: networkCaptureEnabled,
        urlHints: networkCaptureHints,
      },
      location.origin
    );
  } catch (_) {}

  try {
    const result = await browser.runtime.sendMessage({
      type: enabled ? "ai-capture-enable" : "ai-capture-disable",
      url: location.href,
      urlHints: networkCaptureHints,
    });
    return result && result.ok ? "ok" : "unavailable";
  } catch (e) {
    return "error:" + String(e && (e.message || e) || e);
  }
}

globalThis.__YBROWSER_ENABLE_NETWORK_CAPTURE__ = (urlHints) =>
  setNetworkCapture(true, urlHints);
globalThis.__YBROWSER_DISABLE_NETWORK_CAPTURE__ = () => setNetworkCapture(false);

globalThis.__YBROWSER_CWA_CANONICAL_READ__ = (
  conversationId,
  targetWindowId,
  targetPageUrl,
  includeAllPages = true,
  timeoutMs = 45000
) => new Promise((resolve) => {
  const requestId =
    "cwa-" + Date.now() + "-" + (++canonicalSequence);
  const safeTimeout =
    Math.max(5000, Number(timeoutMs) || 45000);
  const timer = setTimeout(() => {
    const pending = pendingCanonicalReads.get(requestId);
    if (!pending) return;
    pendingCanonicalReads.delete(requestId);
    pending.resolve({
      ok: false,
      reasonCode: "CANONICAL_READ_BRIDGE_TIMEOUT",
    });
  }, safeTimeout + 3000);

  pendingCanonicalReads.set(requestId, {
    resolve,
    timer,
  });

  try {
    window.postMessage(
      {
        source: "ybrowser-ai-extension",
        type: "cwa-canonical-read",
        requestId,
        conversationId: String(conversationId || ""),
        targetWindowId: String(targetWindowId || ""),
        targetPageUrl: String(targetPageUrl || ""),
        includeAllPages: includeAllPages === true,
        timeoutMs: safeTimeout,
      },
      location.origin
    );
  } catch (_) {
    clearTimeout(timer);
    pendingCanonicalReads.delete(requestId);
    resolve({
      ok: false,
      reasonCode: "CANONICAL_READ_BRIDGE_POST_FAILED",
    });
  }
});

browser.runtime.onMessage.addListener((message) => {
  if (!message || message.type !== "ai-network") return;
  emitEvent("ai-network", message.payload || {});
  return Promise.resolve({ ok: true });
});

window.addEventListener("message", (event) => {
  if (event.source !== window) return;
  const data = event.data;
  if (!data || data.source !== "ybrowser-ai-page") return;

  if (data.type === "cwa-canonical-read-result") {
    const requestId = String(data.requestId || "");
    const pending = pendingCanonicalReads.get(requestId);
    if (!pending) return;
    clearTimeout(pending.timer);
    pendingCanonicalReads.delete(requestId);
    pending.resolve(
      data.result && typeof data.result === "object"
        ? data.result
        : {
            ok: false,
            reasonCode: "CANONICAL_READ_BRIDGE_RESULT_INVALID",
          }
    );
    return;
  }

  if (data.type === "network") {
    const payload = data.payload || {};
    if (
      networkCaptureEnabled ||
      payload.canonicalRead === true
    ) {
      emitEvent(
        "ai-page-network",
        payload
      );
    }
  }
});

function connect() {
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
    port.onDisconnect.addListener(() => {
      port = null;
      scheduleReconnect();
    });
    port.onMessage.addListener(async (message) => {
      if (!message || message.type !== "rpc") return;
      let value = null;
      let error = "";
      try {
        const result = await runRpc(message.code);
        value = JSON.stringify(result === undefined ? null : result);
      } catch (e) {
        error = String(e && (e.stack || e.message) || e);
      }
      try {
        port?.postMessage({
          type: "rpc-result",
          requestId: message.requestId,
          value,
          error,
        });
      } catch (_) {
        scheduleReconnect();
      }
    });
    port.postMessage({ type: "rpc-ready", url: location.href });
  } catch (_) {
    port = null;
    scheduleReconnect();
  }
}

connect();
