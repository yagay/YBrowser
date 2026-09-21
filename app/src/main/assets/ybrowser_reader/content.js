"use strict";

const NATIVE_APP = "com.yagay.YBrowser.reader";
let port = null;
let reconnectTimer = null;
const executedUserScripts = new Set();

const ybrowserUsesBackgroundVideoVisibilityFix =
  /(^|\.)youtube(?:-nocookie)?\.com$/.test(location.hostname);

if (ybrowserUsesBackgroundVideoVisibilityFix) {
  try {
    Object.defineProperties(document.wrappedJSObject || document, {
      hidden: { value: false },
      visibilityState: { value: "visible" },
    });
  } catch (_) {
    try {
      Object.defineProperties(document, {
        hidden: { get: () => false },
        visibilityState: { get: () => "visible" },
      });
    } catch (_) { }
  }
  window.addEventListener("visibilitychange", (event) => {
    event.stopImmediatePropagation();
  }, true);
}

let currentMedia = null;
let lastMediaReport = 0;

function mediaCandidates() {
  return Array.from(document.querySelectorAll("video,audio"))
    .filter((media) => media && media.isConnected);
}

function chooseMedia() {
  if (currentMedia && currentMedia.isConnected && !currentMedia.ended) return currentMedia;
  currentMedia = mediaCandidates().sort((first, second) => {
    const playingDifference = Number(first.paused) - Number(second.paused);
    if (playingDifference !== 0) return playingDifference;
    return (second.clientWidth * second.clientHeight) -
      (first.clientWidth * first.clientHeight);
  })[0] || null;
  return currentMedia;
}

function reportMediaState(force = false) {
  const media = chooseMedia();
  if (!media || !port) return;
  const now = Date.now();
  if (!force && now - lastMediaReport < 1000) return;
  lastMediaReport = now;
  try {
    port.postMessage({
      type: "media-state",
      title: document.title || "网页媒体",
      url: location.href,
      playing: !media.paused && !media.ended,
      durationMs: Number.isFinite(media.duration) ? Math.round(media.duration * 1000) : -1,
      positionMs: Number.isFinite(media.currentTime) ? Math.round(media.currentTime * 1000) : 0,
    });
  } catch (_) { }
}

function mediaCommand(command) {
  const media = chooseMedia();
  if (!media) return false;
  if (command === "play") {
    media.play().catch(() => {});
  } else if (command === "pause") {
    media.pause();
  } else if (command === "toggle") {
    if (media.paused || media.ended) media.play().catch(() => {});
    else media.pause();
  } else if (command === "stop") {
    media.pause();
    try { media.currentTime = 0; } catch (_) { }
  }
  setTimeout(() => reportMediaState(true), 100);
  return true;
}

document.addEventListener("play", (event) => {
  if (event.target instanceof HTMLMediaElement) {
    currentMedia = event.target;
    reportMediaState(true);
  }
}, true);
document.addEventListener("pause", (event) => {
  if (event.target instanceof HTMLMediaElement) {
    currentMedia = event.target;
    reportMediaState(true);
  }
}, true);
document.addEventListener("ended", (event) => {
  if (event.target instanceof HTMLMediaElement) {
    currentMedia = event.target;
    reportMediaState(true);
  }
}, true);
document.addEventListener("loadedmetadata", (event) => {
  if (event.target instanceof HTMLMediaElement) {
    currentMedia = event.target;
    reportMediaState(true);
  }
}, true);
document.addEventListener("timeupdate", () => reportMediaState(false), true);


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
      if (!message) return;
      if (message.type === "extract-reader") {
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
        return;
      }
      if (message.type === "media-command" && typeof message.command === "string") {
        mediaCommand(message.command);
        return;
      }
      if (message.type === "set-muted") {
        const muted = Boolean(message.muted);
        mediaCandidates().forEach((media) => {
          try { media.muted = muted; } catch (_) { }
        });
      }
      if (message.type === "user-scripts" && Array.isArray(message.scripts)) {
        const host = location.hostname.toLowerCase();
        const matches = (patternRaw) => {
          const pattern = String(patternRaw || "")
            .toLowerCase()
            .replace(/^https?:\/\//, "")
            .split("/")[0]
            .replace(/\.$/, "");
          if (pattern === "*" || pattern === "<all_urls>") return true;
          if (pattern.startsWith("*.")) {
            const base = pattern.slice(2);
            return host === base || host.endsWith("." + base);
          }
          return host === pattern || host.endsWith("." + pattern);
        };
        message.scripts.forEach((script) => {
          if (!script || !matches(script.match)) return;
          const key = String(script.id || script.name || script.match);
          if (executedUserScripts.has(key)) return;
          try {
            Function(String(script.code || ""))();
            executedUserScripts.add(key);
          } catch (error) {
            console.error("YBrowser user script failed", script.name, error);
          }
        });
      }
    });
    port.postMessage({ type: "reader-ready", url: location.href });
    reportMediaState(true);
  } catch (_) {
    port = null;
    scheduleReconnect();
  }
}

connect();
