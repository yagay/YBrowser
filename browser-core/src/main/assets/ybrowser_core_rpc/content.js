"use strict";

const NATIVE_APP = "com.yagay.browsercore.rpc";
let port = null;
let reconnectTimer = null;

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
