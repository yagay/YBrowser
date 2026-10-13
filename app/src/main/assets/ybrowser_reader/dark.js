"use strict";
/* Shared dark fallback for GeckoView and WebView. No page data leaves the device. */
(function () {
  if (globalThis.YBrowserDarkPage) return;
  const flag = "data-ybrowser-dark-active";
  const styleId = "ybrowser-dark-page-style";
  const css = [
    'html[' + flag + '="1"] { filter: invert(1) hue-rotate(180deg) !important; }',
    'html[' + flag + '="1"] input, html[' + flag + '="1"] select,',
    'html[' + flag + '="1"] textarea, html[' + flag + '="1"] button { color-scheme: light !important; }',
    'html[' + flag + '="1"] img, html[' + flag + '="1"] video,',
    'html[' + flag + '="1"] svg, html[' + flag + '="1"] canvas,',
    'html[' + flag + '="1"] input[type="image"] {',
    '  filter: invert(1) hue-rotate(180deg) !important;',
    '}',
  ].join("\n");
  let mode = "OFF";
  let observer = null;
  let timer = null;
  // Same-tab, same-origin cache. A Gecko page can restore its last successful
  // appearance at document_start, before the native messaging port reconnects.
  // Native settings still win as soon as they arrive. No remote requests.
  const cacheKey = "ybrowser.page.dark.mode.v1";
  function rememberMode(value) {
    try { window.sessionStorage.setItem(cacheKey, value); } catch (_) {}
  }
  function cachedMode() {
    try {
      const value = window.sessionStorage.getItem(cacheKey);
      return value === "AUTO" || value === "FORCE" ? value : null;
    } catch (_) { return null; }
  }

  function brightness(color) {
    const value = String(color || "").trim();
    // rgb()/rgba() use 0..255 channels; color(srgb) uses 0..1. Never
    // interpret an opaque white display-p3 canvas as near-black.
    const m = value.match(/[\d.]+/g);
    if (!m || m.length < 3) return null;
    const normalized = /^color\(/i.test(value);
    const scale = normalized ? 255 : 1;
    const channelPart = normalized ? value.replace(/^color\([^\s]+\s*/, "") : value;
    const numbers = normalized ? (channelPart.match(/[\d.]+/g) || []) : m;
    if (numbers.length < 3) return null;
    const alpha = numbers.length > 3 ? Number(numbers[3]) : 1;
    if (alpha < 0.8) return null;
    return (Number(numbers[0]) * 0.2126 +
      Number(numbers[1]) * 0.7152 + Number(numbers[2]) * 0.0722) * scale;
  }

  function appearance(node) {
    if (!node) return null;
    try {
      const style = getComputedStyle(node);
      return {
        background: brightness(style.backgroundColor),
        foreground: brightness(style.color),
        scheme: String(style.colorScheme || "").toLowerCase(),
      };
    } catch (_) { return null; }
  }

  // Large content surfaces can be the actual visible page while <body> is a
  // different color (e.g. Google results, overlays and embedded reader apps).
  // A small white card should not turn an otherwise dark site inside-out.
  function dominatesViewport(node) {
    if (!node || typeof node.getBoundingClientRect !== "function") return false;
    const w = Number(window.innerWidth) || 0;
    const h = Number(window.innerHeight) || 0;
    if (w <= 0 || h <= 0) return false;
    try {
      const box = node.getBoundingClientRect();
      const width = Math.min(Math.max(box.width, 0), w);
      const height = Math.min(Math.max(box.height, 0), h);
      return width * height >= w * h * 0.55;
    } catch (_) { return false; }
  }

  function isLightPage() {
    const root = document.documentElement;
    if (!root) return false;
    const main = document.querySelector("main") ||
      document.querySelector('[role="main"]');
    const mainLook = appearance(main);
    const bodyLook = appearance(document.body);
    const rootLook = appearance(root);

    // Prioritize a dominant content panel over a contrasting site shell.
    if (mainLook?.background !== null &&
        mainLook?.background !== undefined && dominatesViewport(main)) {
      return mainLook.background >= 155;
    }
    // Then pick the main page canvas. Transparent backgrounds are skipped.
    for (const look of [bodyLook, rootLook, mainLook]) {
      if (look?.background === null || look?.background === undefined) continue;
      return look.background >= 155;
    }
    const darkCanvas = rootLook?.scheme.includes("dark") ||
      bodyLook?.scheme.includes("dark");
    if (!darkCanvas) return true;

    // FORCE can correct an explicitly dark color-scheme combined with a
    // visibly light design: dark foreground on a transparent page canvas.
    // AUTO respects the website's explicit native dark canvas instead.
    return mode === "FORCE" &&
      bodyLook?.foreground !== null &&
      bodyLook?.foreground !== undefined &&
      bodyLook.foreground < 100;
  }

  let constructedSheet = null;

  function ensureStyle(root) {
    // Prefer CSSOM injection in Chromium WebView. Constructable sheets avoid
    // inline <style> restrictions on sites with a strict style-src CSP.
    try {
      if (typeof CSSStyleSheet === "function" &&
          Array.isArray(document.adoptedStyleSheets)) {
        if (!constructedSheet) {
          const sheet = new CSSStyleSheet();
          sheet.replaceSync(css);
          constructedSheet = sheet;
        }
        if (!document.adoptedStyleSheets.includes(constructedSheet)) {
          document.adoptedStyleSheets = [
            ...document.adoptedStyleSheets, constructedSheet,
          ];
        }
        return;
      }
    } catch (_) {
      constructedSheet = null;
    }
    // Older WebView fallback. Gecko also has a declarative extension CSS file,
    // so its styling does not depend on this DOM-based fallback.
    let style = document.getElementById(styleId);
    if (!style) {
      style = document.createElement("style");
      style.id = styleId;
      (document.head || root).appendChild(style);
    }
    if (style.textContent !== css) style.textContent = css;
  }

  function refresh() {
    timer = null;
    const root = document.documentElement;
    if (!root) return;
    const enable = mode !== "OFF" && isLightPage();
    if (enable) ensureStyle(root);
    // No redundant DOM mutations on fast-scrolling / SPA pages.
    if (enable && root.getAttribute(flag) !== "1") {
      root.setAttribute(flag, "1");
    } else if (!enable && root.hasAttribute(flag)) {
      root.removeAttribute(flag);
    }
  }

  function schedule() {
    if (timer === null) timer = setTimeout(refresh, 150);
  }

  function setMode(raw) {
    const next = raw === "AUTO" || raw === "FORCE" ? raw : "OFF";
    if (mode !== next) {
      mode = next;
      rememberMode(next);
      if (observer) observer.disconnect();
      observer = null;
      if (mode !== "OFF") {
        observer = new MutationObserver((records) => {
          if (records.some((r) =>
            r.target.id !== styleId &&
            !(r.type === "attributes" && r.attributeName === flag)
          )) schedule();
        });
        observer.observe(document, {
          childList: true, subtree: true, attributes: true,
          attributeFilter: ["class", "style", "data-theme", "data-color-scheme"],
        });
      }
    }
    refresh();
  }

  globalThis.YBrowserDarkPage = { setMode };
  // Reapply before the first paint on a repeat visit; the bridge corrects
  // stale preferences after navigation without requiring a page reload.
  const restoredMode = cachedMode();
  if (restoredMode !== null) setMode(restoredMode);
  document.addEventListener("DOMContentLoaded", schedule, { once: true });
  window.addEventListener("pageshow", schedule);
  window.addEventListener("popstate", schedule);
  try {
    window.matchMedia("(prefers-color-scheme: dark)")
      .addEventListener("change", schedule);
  } catch (_) {}
})();
