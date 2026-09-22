(() => {
  const api = window.__AIHUB__;
  const cfg = window.__AIHUB_CONFIG__ || {};
  if (!api || typeof api.diagnosticSnapshot !== "function") return;

  const original = api.diagnosticSnapshot.bind(api);

  const hash = (value) => {
    let h = 2166136261;
    const source = String(value || "");
    for (let i = 0; i < source.length; i++) {
      h ^= source.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return (h >>> 0).toString(16);
  };

  const redactPath = (path) => String(path || "").split("/").map((segment) => {
    if (segment.length >= 16 && /^[A-Za-z0-9_-]+$/.test(segment)) return "<id>";
    if (segment.length >= 16 && /^[0-9a-f-]+$/i.test(segment)) return "<id>";
    return segment;
  }).join("/");

  const visible = (node) => {
    if (!node?.getBoundingClientRect) return false;
    const rect = node.getBoundingClientRect();
    const style = getComputedStyle(node);
    return rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden";
  };

  const all = (selectors) => {
    const out = [];
    const seen = new Set();
    (selectors || []).forEach((selector) => {
      try {
        document.querySelectorAll(selector).forEach((node) => {
          if (!seen.has(node)) {
            seen.add(node);
            out.push(node);
          }
        });
      } catch (_) {}
    });
    return out;
  };

  const classList = (node) => {
    const raw = typeof node?.className === "string" ? node.className : node?.getAttribute?.("class") || "";
    return raw.split(/\s+/).filter(Boolean).slice(0, 8);
  };

  const nodeMeta = (node) => {
    if (!node) return null;
    return {
      tag: node.tagName || "",
      classes: classList(node),
      role: String(node.getAttribute?.("role") || "").slice(0, 64),
      testid: String(node.getAttribute?.("data-testid") || node.getAttribute?.("data-test-id") || "").slice(0, 100),
      aria: String(node.getAttribute?.("aria-label") || "").slice(0, 120),
      state: String(node.getAttribute?.("data-state") || "").slice(0, 64),
      disabled: !!node.disabled,
      ariaDisabled: String(node.getAttribute?.("aria-disabled") || "").slice(0, 16),
      visible: visible(node),
      textChars: String(node.innerText || node.textContent || "").length,
      childCount: node.children?.length || 0
    };
  };

  const tree = (node, depth = 0, maxDepth = 3) => {
    if (!node || depth > maxDepth) return null;
    const children = depth === maxDepth
      ? []
      : Array.from(node.children || []).slice(0, 12).map((child) => tree(child, depth + 1, maxDepth)).filter(Boolean);
    return { ...nodeMeta(node), children };
  };

  const iconSignature = (node) => {
    if (!node) return null;
    const svg = node.querySelector?.("svg");
    const paths = Array.from(node.querySelectorAll?.("svg path, svg use, svg polyline, svg polygon, svg rect, svg circle") || [])
      .slice(0, 12)
      .map((it) => [
        it.tagName,
        it.getAttribute?.("d"),
        it.getAttribute?.("href"),
        it.getAttribute?.("xlink:href"),
        it.getAttribute?.("points"),
        it.getAttribute?.("class")
      ].filter(Boolean).join("|"));
    const raw = [
      svg?.getAttribute?.("viewBox") || "",
      svg?.getAttribute?.("data-icon") || "",
      svg?.getAttribute?.("aria-label") || "",
      paths.join(";")
    ].join("|");
    return {
      hash: hash(raw),
      svg: !!svg,
      viewBox: String(svg?.getAttribute?.("viewBox") || "").slice(0, 80),
      dataIcon: String(svg?.getAttribute?.("data-icon") || "").slice(0, 80),
      partCount: paths.length
    };
  };

  api.diagnosticSnapshot = (phase) => {
    const snap = original(phase) || {};
    if (snap.page) {
      snap.page.path = redactPath(snap.page.path);
      snap.page.pathHash = hash(location.pathname);
    }

    const turns = all(cfg.turnSelectors || []);
    snap.turnTrees = turns.slice(-3).map((node) => tree(node, 0, 3));

    const sendCandidates = all(cfg.sendProbeSelectors || cfg.sendSelectors || []);
    const sendNode = sendCandidates.find(visible) || sendCandidates[0] || null;
    const stopCandidates = all(cfg.stopSelectors || []);
    const stopNode = stopCandidates.find(visible) || stopCandidates[0] || null;
    snap.controlSignatures = {
      send: iconSignature(sendNode),
      stop: iconSignature(stopNode)
    };

    if (typeof api.generationState === "function") {
      try { snap.responseState = api.generationState(); } catch (_) {}
    }

    const net = window.__AIHUB_NET_DIAG__;
    snap.network = net?.events?.slice(-40) || [];
    return snap;
  };
})();
