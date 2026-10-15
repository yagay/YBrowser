"use strict";
/* Shared dark fallback for GeckoView and WebView. No page data leaves the device. */
(function () {
  if (globalThis.YBrowserDarkPage) return;
  const flag = "data-ybrowser-dark-active";
  const styleId = "ybrowser-dark-page-style";
  const css = [
  "html[data-ybrowser-dark-active=\"1\"] { color-scheme: dark !important; background-color: #17191d !important; }",
  "html[data-ybrowser-dark-active=\"1\"] [data-ybrowser-dark-bg=\"1\"] { background-color: var(--ybrowser-dark-bg) !important; }",
  "html[data-ybrowser-dark-active=\"1\"] [data-ybrowser-dark-fg=\"1\"] { color: var(--ybrowser-dark-fg) !important; -webkit-text-fill-color: var(--ybrowser-dark-fg) !important; }",
  "html[data-ybrowser-dark-active=\"1\"] [data-ybrowser-dark-border=\"1\"] { border-color: var(--ybrowser-dark-border) !important; }",
  "html[data-ybrowser-dark-active=\"1\"] [data-ybrowser-dark-bg=\"1\"]:is(input, select, textarea, button) { color-scheme: dark !important; }",
  "html[data-ybrowser-dark-active=\"1\"] [data-ybrowser-dark-fg=\"1\"]::placeholder { color: #9ca5b1 !important; -webkit-text-fill-color: #9ca5b1 !important; }"
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
      // Viewport intersection, not the full page's scrollable area. A large
      // element that has scrolled off screen must not control today's theme.
      const left = Number(box.left) || 0;
      const top = Number(box.top) || 0;
      const right = Number.isFinite(Number(box.right))
        ? Number(box.right) : left + (Number(box.width) || 0);
      const bottom = Number.isFinite(Number(box.bottom))
        ? Number(box.bottom) : top + (Number(box.height) || 0);
      const width = Math.max(0, Math.min(right, w) - Math.max(left, 0));
      const height = Math.max(0, Math.min(bottom, h) - Math.max(top, 0));
      return width * height >= w * h * 0.55;
    } catch (_) { return false; }
  }

  function findMainSurface() {
    // Google Search and several single-page apps use #main / #search rather
    // than a semantic <main> element. Prefer a large visible candidate.
    let fallback = null;
    for (const selector of ["main", '[role="main"]', "#main", "#search"]) {
      let node = null;
      try { node = document.querySelector(selector); } catch (_) {}
      if (!node) continue;
      if (!fallback) fallback = node;
      if (dominatesViewport(node)) return node;
    }
    return fallback;
  }

  function visibleSurfaceTone() {
    // A 3x3 viewport sample finds opaque backgrounds on sites that only paint
    // nested <div> containers. Unlike scanning a single center point, this
    // avoids treating a small bright card as the entire page.
    if (typeof document.elementFromPoint !== "function") return null;
    const w = Number(window.innerWidth) || 0;
    const h = Number(window.innerHeight) || 0;
    if (w < 1 || h < 1) return null;
    let light = 0, dark = 0;
    for (const y of [0.2, 0.5, 0.8]) {
      for (const x of [0.2, 0.5, 0.8]) {
        let node = null;
        try { node = document.elementFromPoint(w * x, h * y); } catch (_) {}
        for (let depth = 0; node && depth < 12; depth++, node = node.parentElement) {
          const value = appearance(node)?.background;
          if (value === null || value === undefined) continue;
          if (value >= 155) light++;
          else dark++;
          break;
        }
      }
    }
    if (light >= 6) return true;
    if (dark >= 6) return false;
    return null;
  }

  function isLightPage(sampledTone) {
    const root = document.documentElement;
    if (!root) return false;
    const main = findMainSurface();
    const mainLook = appearance(main);
    const bodyLook = appearance(document.body);
    const rootLook = appearance(root);

    // Prefer actual *painted* surfaces over a nominal white main/body canvas.
    // Google and other SPAs draw their content in nested elements whose
    // backgrounds can change without changing <body> or <main>.
    if (sampledTone !== null && sampledTone !== undefined) return sampledTone;
    // Where DOM point sampling is unavailable, use the dominant main panel.
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


  // Recolor only page surfaces and their text. A filter on <html> also changes
  // raster graphics, icon fonts, SVG, canvas, gradients and third-party UI.
  const bgFlag = "data-ybrowser-dark-bg";
  const fgFlag = "data-ybrowser-dark-fg";
  const borderFlag = "data-ybrowser-dark-border";
  const paintStates = new WeakMap();

  function rgb(raw) {
    const matched = /^rgba?\(([^)]+)\)$/i.exec(String(raw || "").trim());
    if (!matched) return null;
    const tokens = matched[1].split(/[,\s/]+/).filter(Boolean);
    if (tokens.length < 3) return null;
    const channel = (value) => {
      const n = Number.parseFloat(value);
      return Number.isFinite(n)
        ? Math.max(0, Math.min(255, value.endsWith("%") ? n * 2.55 : n))
        : null;
    };
    const r = channel(tokens[0]), g = channel(tokens[1]), b = channel(tokens[2]);
    if (r === null || g === null || b === null) return null;
    const a = tokens[3] === undefined ? 1 : Number.parseFloat(tokens[3]);
    if (!Number.isFinite(a) || a < .12) return null;
    return { r, g, b, a, luma: r * .2126 + g * .7152 + b * .0722 };
  }

  function darkBackground(c) {
    const base = 27 + (255 - c.luma) * .12;
    const tint = (v) => Math.max(20, Math.min(73, Math.round(base + (v - c.luma) * .10)));
    const value = [tint(c.r), tint(c.g), tint(c.b)].join(", ");
    return c.a < .98 ? "rgba(" + value + ", " + c.a + ")" : "rgb(" + value + ")";
  }

  function readableForeground(c) {
    if (Math.max(c.r, c.g, c.b) - Math.min(c.r, c.g, c.b) < 45) {
      return c.luma < 95 ? "#e3e7ed" : "#b7c0cb";
    }
    // Preserve links' hues while making darker text readable.
    return "rgb(" + [c.r, c.g, c.b]
      .map((v) => Math.round(133 + v * .40)).join(", ") + ")";
  }

  function setPaint(node, attribute, variable, value) {
    if (value === null) {
      if (node.hasAttribute(attribute)) node.removeAttribute(attribute);
      if (node.style.getPropertyValue(variable)) node.style.removeProperty(variable);
    } else {
      if (node.getAttribute(attribute) !== "1") node.setAttribute(attribute, "1");
      if (node.style.getPropertyValue(variable) !== value) node.style.setProperty(variable, value);
    }
  }

  function paintPage(root) {
    const stack = [root];
    let visited = 0;
    const skips = new Set([
      "SVG", "CANVAS", "IMG", "PICTURE", "VIDEO", "AUDIO", "IFRAME",
      "OBJECT", "EMBED", "SOURCE", "SCRIPT", "STYLE", "NOSCRIPT",
      "MATH", "TEMPLATE",
    ]);
    // Descendants inherit the ORIGINAL background semantics. A white chart
    // container kept unchanged must also keep its dark labels unchanged.
    while (stack.length && visited < 6500) {
      const node = stack.pop();
      if (!(node instanceof Element) || skips.has(node.tagName)) continue;
      visited++;
      const parent = paintStates.get(node.parentElement) ||
        { converted: true, originalLight: true };
      let style;
      try { style = getComputedStyle(node); } catch (_) { continue; }
      const ownBg = rgb(style.backgroundColor);
      const fg = rgb(style.color);
      const graphicBackground = style.backgroundImage !== "none" ||
        style.backgroundClip === "text" ||
        style.webkitBackgroundClip === "text";
      const graphicChild = node !== root && node !== document.body &&
        node.children.length <= 8 &&
        Array.from(node.children).some((child) =>
          child.matches("canvas, svg, iframe, object, embed"));
      const preserveGraphic = graphicBackground || graphicChild;
      const solidBg = ownBg && ownBg.a >= .75;
      let originalLight = solidBg ? ownBg.luma >= 165 : parent.originalLight;
      let converted = solidBg ? false : parent.converted;
      let background = null;
      if (!preserveGraphic && ownBg && ownBg.luma >= 165) {
        background = darkBackground(ownBg);
        originalLight = true;
        converted = true;
      } else if (preserveGraphic) {
        converted = false;
      }
      // If an element is on a painted dark panel or a protected image/gradient,
      // keep the site's intended colors; never make its white labels invisible.
      const textFillIsTransparent = style.webkitTextFillColor === "transparent" ||
        style.webkitTextFillColor === "rgba(0, 0, 0, 0)";
      const recolorText = converted && originalLight && fg &&
        fg.luma < 160 && !textFillIsTransparent;
      setPaint(node, bgFlag, "--ybrowser-dark-bg", background);
      setPaint(node, fgFlag, "--ybrowser-dark-fg",
        recolorText ? readableForeground(fg) : null);
      const border = rgb(style.borderTopColor);
      const borderPaint = converted && border && border.luma > 135 &&
        style.borderTopStyle !== "none" && style.borderTopWidth !== "0px"
        ? "#454b54" : null;
      setPaint(node, borderFlag, "--ybrowser-dark-border", borderPaint);
      paintStates.set(node, { converted, originalLight });
      for (let i = node.children.length - 1; i >= 0; i--) stack.push(node.children[i]);
    }
  }

  let constructedSheet = null;
  // GeckoView injects dark.css declaratively at document_start. The WebView
  // renderer has no extension CSS and uses constructable/inline styles below.
  // Avoid maintaining two active sheets in the same Gecko document.
  const hasGeckoExtensionCss = typeof browser !== "undefined" &&
    browser?.runtime?.id === "ybrowser-reader@yagay.com";

  function ensureStyle(root) {
    if (hasGeckoExtensionCss) return;
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

  // Pin the light/dark decision to page colors, not viewport geometry.
  // Google Search adds results while scrolling. Re-evaluating the viewport
  // bounds after every lazy-load can otherwise alternate the root filter and
  // flash the whole surface despite no actual website theme change.
  let lastSurfaceFingerprint = null;
  let lastMainNode = null;
  let lastLightDecision = null;
  let lastVisibleTone = null;
  // undefined is no pending candidate; null is a real unknown sample.
  let pendingVisibleTone = undefined;

  function surfaceFingerprint(root, main) {
    const describe = (node) => {
      const look = appearance(node);
      if (!look) return "missing";
      return [look.background, look.foreground, look.scheme].join("|");
    };
    return [describe(root), describe(document.body), describe(main)].join(";");
  }


  function refresh() {
    timer = null;
    const root = document.documentElement;
    if (!root) return;
    // Sample native computed colors, not the fallback's own overrides.
    // This is synchronous, avoiding the visible flash of a theme toggle.
    if (observer) observer.disconnect();
    if (root.hasAttribute(flag)) root.removeAttribute(flag);
    try {
      const main = findMainSurface();
      const fingerprint = surfaceFingerprint(root, main);
      const baseChanged = lastLightDecision === null ||
        lastSurfaceFingerprint !== fingerprint || lastMainNode !== main;
      const tone = mode === "OFF" ? null : visibleSurfaceTone();
      if (mode !== "OFF" && baseChanged) {
        lastLightDecision = isLightPage(tone);
        lastVisibleTone = tone;
        pendingVisibleTone = undefined;
      } else if (mode !== "OFF") {
        if (tone === lastVisibleTone) {
          pendingVisibleTone = undefined;
        } else if (pendingVisibleTone === tone) {
          lastLightDecision = isLightPage(tone);
          lastVisibleTone = tone;
          pendingVisibleTone = undefined;
        } else {
          pendingVisibleTone = tone;
          setTimeout(refresh, 350);
        }
      }
      lastSurfaceFingerprint = fingerprint;
      lastMainNode = main;
      const enable = mode !== "OFF" && lastLightDecision === true;
      if (enable) {
        ensureStyle(root);
        paintPage(root);
        root.setAttribute(flag, "1");
      }
    } finally {
      if (observer) {
        observer.observe(document, {
          childList: true, subtree: true, attributes: true,
          attributeFilter: ["class", "style", "data-theme", "data-color-scheme"],
        });
      }
    }
  }

  function schedule() {
    if (timer === null) timer = setTimeout(refresh, 150);
  }

  function setMode(raw) {
    const next = raw === "AUTO" || raw === "FORCE" ? raw : "OFF";
    if (mode !== next) {
      mode = next;
      // A FORCE/AUTO switch can change classification without CSS changes.
      lastSurfaceFingerprint = null;
      lastVisibleTone = null;
      pendingVisibleTone = undefined;
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
