"use strict";

const NATIVE_APP = "com.yagay.browsercore.rpc";
let port = null;
let reconnectTimer = null;
let networkCaptureEnabled = false;
let networkCaptureHints = [];

const RPC_RESULT_INLINE_CHARS = 192 * 1024;
const RPC_RESULT_CHUNK_BYTES = 288 * 1024;

async function sha256Hex(bytes) {
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", bytes)
  );
  return Array.from(
    digest,
    (value) => value.toString(16).padStart(2, "0")
  ).join("");
}

function bytesToBase64(bytes) {
  let binary = "";
  const block = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += block) {
    const part = bytes.subarray(
      offset,
      Math.min(offset + block, bytes.length)
    );
    binary += String.fromCharCode(...part);
  }
  return btoa(binary);
}

async function postRpcResult(requestId, value, error) {
  if (error || typeof value !== "string" || value.length <= RPC_RESULT_INLINE_CHARS) {
    port?.postMessage({
      type: "rpc-result",
      requestId,
      value,
      error: error || "",
    });
    return;
  }

  const bytes = new TextEncoder().encode(value);
  const sha256 = await sha256Hex(bytes);
  const chunkCount = Math.max(
    1,
    Math.ceil(bytes.length / RPC_RESULT_CHUNK_BYTES)
  );

  for (let chunkIndex = 0; chunkIndex < chunkCount; chunkIndex += 1) {
    const data = bytes.subarray(
      chunkIndex * RPC_RESULT_CHUNK_BYTES,
      Math.min(
        (chunkIndex + 1) * RPC_RESULT_CHUNK_BYTES,
        bytes.length
      )
    );
    port?.postMessage({
      type: "rpc-result-chunk",
      requestId,
      chunkIndex,
      chunkCount,
      totalBytes: bytes.length,
      sha256,
      data: bytesToBase64(data),
    });
  }

  port?.postMessage({
    type: "rpc-result-end",
    requestId,
    chunkCount,
    totalBytes: bytes.length,
    sha256,
  });
}

function scheduleReconnect() {
  if (reconnectTimer !== null) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, 400);
}

async function runRpc(code) {
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  const fn = new AsyncFunction(String(code || ""));
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

browser.runtime.onMessage.addListener((message) => {
  if (!message || message.type !== "ai-network") return;
  emitEvent("ai-network", message.payload || {});
  return Promise.resolve({ ok: true });
});

window.addEventListener("message", (event) => {
  if (event.source !== window || !networkCaptureEnabled) return;
  const data = event.data;
  if (!data || data.source !== "ybrowser-ai-page" || data.type !== "network") {
    return;
  }
  emitEvent("ai-page-network", data.payload || {});
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
        const name = String(e?.name || "Error");
        const message = String(e?.message || e || "");
        const stack = String(e?.stack || "");
        error = [name + ": " + message, stack]
          .filter(Boolean)
          .join("\n");
      }
      try {
        await postRpcResult(
          message.requestId,
          value,
          error
        );
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
