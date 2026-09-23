"use strict";

const NATIVE_APP = "com.yagay.browsercore.rpc";
let port = null;
let reconnectTimer = null;
let networkCaptureEnabled = false;
let networkCaptureHints = [];

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

let nextPageApiRequestId = 0;
const pendingPageApi = new Map();

globalThis.__YBROWSER_PAGE_API_REQUEST__ = (operation, payload) =>
  new Promise((resolve, reject) => {
    nextPageApiRequestId =
      nextPageApiRequestId >= Number.MAX_SAFE_INTEGER
        ? 1
        : nextPageApiRequestId + 1;
    const requestId = nextPageApiRequestId;
    const timeout = setTimeout(() => {
      pendingPageApi.delete(requestId);
      reject(new Error("page-api-timeout"));
    }, 30000);

    pendingPageApi.set(requestId, {
      resolve,
      reject,
      timeout,
    });

    try {
      window.postMessage(
        {
          source: "ybrowser-ai-extension",
          type: "page-api",
          requestId,
          operation: String(operation || ""),
          payload: payload || {},
        },
        location.origin
      );
    } catch (error) {
      clearTimeout(timeout);
      pendingPageApi.delete(requestId);
      reject(error);
    }
  });

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

browser.runtime.onMessage.addListener((message) => {
  if (!message || message.type !== "ai-network") return;
  emitEvent("ai-network", message.payload || {});
  return Promise.resolve({ ok: true });
});

window.addEventListener("message", (event) => {
  if (event.source !== window) return;
  const data = event.data;
  if (!data || data.source !== "ybrowser-ai-page") return;

  if (data.type === "page-api-result") {
    const requestId = Number(data.requestId || 0);
    const pending = pendingPageApi.get(requestId);
    if (!pending) return;
    clearTimeout(pending.timeout);
    pendingPageApi.delete(requestId);

    if (data.ok === false) {
      pending.reject(
        new Error(String(data.error || "page-api-failed"))
      );
    } else {
      pending.resolve(data.result ?? null);
    }
    return;
  }

  if (data.type === "network") {
    const payload = data.payload || {};
    const controlledPageApi =
      String(payload.targetWindowId || "").trim().length > 0;
    if (networkCaptureEnabled || controlledPageApi) {
      emitEvent("ai-page-network", payload);
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
