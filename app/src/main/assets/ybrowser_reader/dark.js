"use strict";
/* Shared dark fallback for GeckoView and WebView. No page data leaves the device. */
(function () {
  if (globalThis.YBrowserDarkPage) return;
  const flag = "data-ybrowser-dark-active";
  const styleId = "ybrowser-dark-page-style";
  const css = [
    'html[' + flag + '="1"] { filter: invert(1) hue-rotate(180deg) !important; color-scheme: light !important; }',
    'html[' + flag + '="1"] img, html[' + flag + '="1"] video, html[' + flag + '="1"] canvas {',
    '  filter: invert(1) hue-rotate(180deg) !important;',
    '}',
  ].join("\n");
  let mode = "OFF";
  let observer = null;
  let timer = null;

  function brightness(color) {
    const m = String(color || "").match(/[\d.]+/g);
    if (!m || m.length < 3 || (m.length > 3 && Number(m[3]) < 0.8)) return null;
    return Number(m[0]) * 0.2126 + Number(m[1]) * 0.7152 + Number(m[2]) * 0.0722;
  }

  function isLightPage() {
    let light = false;
    const nodes = [document.body, document.querySelector("main"),
      document.querySelector('[role="main"]'), document.documentElement];
    for (const node of nodes) {
      if (!node) continue;
      const luminance = brightness(getComputedStyle(node).backgroundColor);
      if (luminance !== null) {
        if (luminance < 125) return false;
        if (luminance > 176) light = true;
      }
    }
    if (light) return true;
    // Transparent default background: respect sites with a dark UA canvas.
    const scheme = getComputedStyle(document.documentElement).colorScheme;
    return !String(scheme).includes("dark");
  }

  function refresh() {
    timer = null;
    const root = document.documentElement;
    if (!root) return;
    const enable = mode !== "OFF" && isLightPage();
    if (enable) {
      let style = document.getElementById(styleId);
      if (!style) {
        style = document.createElement("style");
        style.id = styleId;
        (document.head || root).appendChild(style);
      }
      if (style.textContent !== css) style.textContent = css;
    }
    if (enable) root.setAttribute(flag, "1");
    else root.removeAttribute(flag);
  }

  function schedule() {
    if (timer === null) timer = setTimeout(refresh, 150);
  }

  function setMode(raw) {
    const next = raw === "AUTO" || raw === "FORCE" ? raw : "OFF";
    if (mode !== next) {
      mode = next;
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
  document.addEventListener("DOMContentLoaded", schedule, { once: true });
  window.addEventListener("pageshow", schedule);
  window.addEventListener("popstate", schedule);
  try {
    window.matchMedia("(prefers-color-scheme: dark)")
      .addEventListener("change", schedule);
  } catch (_) {}
})();
