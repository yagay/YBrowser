"use strict";

const NATIVE_APP = "com.yagay.YBrowser.reader";
let port = null;
let reconnectTimer = null;

function clean(value, maxLength) {
  return (value || "")
    .replace(/[\u0000-\u001f\u007f]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, maxLength);
}

function extractReaderPayload() {
  const source = document.querySelector("article") ||
    document.querySelector("main") ||
    document.body;
  if (!source) return { error: "missing-root" };

  const root = source.cloneNode(true);
  root.querySelectorAll(
    "script,style,noscript,template,iframe,object,embed,canvas,svg,form,input,button,nav,aside,footer,video,audio"
  ).forEach((node) => node.remove());

  const blocks = [];
  let totalChars = 0;
  root.querySelectorAll("h1,h2,h3,h4,h5,h6,p,blockquote,li").forEach((node) => {
    if (blocks.length >= 600 || totalChars >= 500000) return;
    const text = clean(
      node.innerText || node.textContent,
      Math.min(12000, 500000 - totalChars)
    );
    if (!text || text.length < 2) return;

    const tag = node.tagName.toLowerCase();
    const kind = tag.startsWith("h") ? "heading" :
      tag === "blockquote" ? "quote" :
      tag === "li" ? "listitem" : "paragraph";

    blocks.push({
      kind,
      level: kind === "heading" ? Number(tag.substring(1)) : 0,
      text,
    });
    totalChars += text.length;
  });

  return {
    title: clean(document.querySelector('meta[property="og:title"]')?.content, 500) ||
      clean(document.title, 500),
    siteName: clean(document.querySelector('meta[property="og:site_name"]')?.content, 200) ||
      clean(location.hostname, 200),
    sourceUrl: location.href.slice(0, 2048),
    blocks,
  };
}

function scheduleReconnect() {
  if (reconnectTimer !== null) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, 500);
}

function connect() {
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
    port.onDisconnect.addListener(() => {
      port = null;
      scheduleReconnect();
    });
    port.onMessage.addListener((message) => {
      if (!message || message.type !== "extract-reader") return;
      let payload = null;
      try {
        payload = extractReaderPayload();
      } catch (_) {
        payload = { error: "extract-failed" };
      }
      try {
        port?.postMessage({
          type: "reader-result",
          requestId: message.requestId,
          payload,
        });
      } catch (_) {
        scheduleReconnect();
      }
    });
    port.postMessage({ type: "reader-ready", url: location.href });
  } catch (_) {
    port = null;
    scheduleReconnect();
  }
}

connect();
