(() => {
  const api = window.__AIHUB__;
  const cfg = window.__AIHUB_CONFIG__ || {};
  if (!api) return;

  const state = window.__AIHUB_DIAG_STATE__ || (window.__AIHUB_DIAG_STATE__ = {
    mutations: { total: 0, added: 0, removed: 0, attributes: 0, lastAt: 0, tags: {} },
    errors: [], observerInstalled: false, errorsInstalled: false
  });

  const safe = (value, max = 160) => String(value || "")
    .replace(/https?:\/\/[^\s?#]+(?:\?[^\s#]*)?(?:#[^\s]*)?/gi, "<url>")
    .replace(/(authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|id[_-]?token|token)\s*[:=]\s*[^\s,;]+/gi, "$1=<redacted>")
    .replace(/\s+/g, " ").trim().slice(0, max);

  const visible = (node) => {
    if (!node?.getBoundingClientRect) return false;
    const rect = node.getBoundingClientRect();
    const style = getComputedStyle(node);
    return rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden" && Number(style.opacity || 1) !== 0;
  };

  const usable = (node) => !!node && visible(node) && !node.disabled && node.getAttribute?.("aria-disabled") !== "true" && !node.classList?.contains("ds-button--disabled");
  const textLen = (node) => {
    if (!node) return 0;
    if (node instanceof HTMLTextAreaElement || node instanceof HTMLInputElement) return String(node.value || "").length;
    return String(node.innerText || node.textContent || "").length;
  };

  const keywords = (node) => {
    const value = [node?.getAttribute?.("aria-label"), node?.getAttribute?.("title"), node?.getAttribute?.("data-testid"), node?.innerText]
      .filter(Boolean).join(" ").toLowerCase();
    const defs = [
      ["send", /send|submit|发送|提交/], ["stop", /stop|停止/], ["attach", /attach|upload|file|photo|image|附件|上传|文件|图片/],
      ["model", /model|模型/], ["search", /search|browse|联网|搜索/], ["reason", /reason|think|deepthink|思考|推理/],
      ["research", /research|研究/], ["tool", /tool|工具/], ["copy", /copy|复制/], ["retry", /retry|regenerate|重试|重新生成/],
      ["continue", /continue|继续/], ["edit", /edit|编辑/], ["new", /new chat|new conversation|新对话|新会话/],
      ["delete", /delete|remove|删除/], ["rename", /rename|重命名/], ["voice", /voice|mic|microphone|语音|麦克风/]
    ];
    return defs.filter(([, rx]) => rx.test(value)).map(([name]) => name);
  };

  const classTokens = (node) => {
    const raw = typeof node?.className === "string" ? node.className : (node?.getAttribute?.("class") || "");
    return raw.split(/\s+/).filter(Boolean).slice(0, 8).map((it) => safe(it, 64));
  };

  const summary = (node) => {
    if (!node) return null;
    const rect = node.getBoundingClientRect?.();
    return {
      tag: node.tagName || "", id: safe(node.id, 80), classes: classTokens(node),
      role: safe(node.getAttribute?.("role"), 64), type: safe(node.getAttribute?.("type"), 48),
      name: safe(node.getAttribute?.("name"), 80),
      testid: safe(node.getAttribute?.("data-testid") || node.getAttribute?.("data-test-id"), 120),
      state: safe(node.getAttribute?.("data-state"), 64),
      turn: safe(node.getAttribute?.("data-turn") || node.getAttribute?.("data-message-author-role"), 64),
      aria: safe(node.getAttribute?.("aria-label"), 140), placeholder: safe(node.getAttribute?.("placeholder"), 140),
      ariaDisabled: safe(node.getAttribute?.("aria-disabled"), 16), ariaPressed: safe(node.getAttribute?.("aria-pressed"), 16),
      ariaExpanded: safe(node.getAttribute?.("aria-expanded"), 16), visible: visible(node), disabled: !!node.disabled,
      textChars: textLen(node), childCount: node.children?.length || 0, keywords: keywords(node),
      rect: rect ? { x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width), h: Math.round(rect.height) } : null
    };
  };

  const ancestors = (node, max = 4) => {
    const out = []; let current = node?.parentElement || null;
    while (current && out.length < max) { out.push(summary(current)); current = current.parentElement; }
    return out;
  };

  const all = (selectors) => {
    const out = []; const seen = new Set();
    (selectors || []).forEach((selector) => {
      try { document.querySelectorAll(selector).forEach((node) => { if (!seen.has(node)) { seen.add(node); out.push(node); } }); } catch (_) {}
    });
    return out;
  };

  const group = (selectors) => {
    const nodes = all(selectors); const vis = nodes.filter(visible); const use = nodes.filter(usable);
    return { selectors: (selectors || []).slice(0, 16), count: nodes.length, visible: vis.length, usable: use.length, samples: (vis.length ? vis : nodes).slice(0, 4).map(summary) };
  };

  const fileInputs = () => Array.from(document.querySelectorAll("input[type='file']")).slice(0, 10).map((node) => ({
    ...summary(node), accept: safe(node.accept || "", 500), multiple: !!node.multiple, filesCount: node.files?.length || 0
  }));

  const interactives = () => Array.from(document.querySelectorAll(
    "button,[role='button'],[role='menuitem'],[role='option'],[role='radio'],input[type='button'],input[type='submit']"
  )).filter(visible).slice(0, 60).map(summary);

  const messageStructures = () => {
    const nodes = all([
      "[data-message-author-role]", "[data-turn]", "[data-testid*='conversation-turn']", "article[data-testid]",
      "div.ds-message", "[class*='assistant-message']", "model-response", "message-content", "[data-is-streaming]"
    ]).filter((node) => textLen(node) > 0);
    return nodes.slice(-16).map((node) => ({ ...summary(node), ancestors: ancestors(node, 2) }));
  };

  const resources = () => {
    const entries = performance.getEntriesByType?.("resource") || []; const byType = {}; let bytes = 0;
    entries.forEach((entry) => { const kind = String(entry.initiatorType || "other"); byType[kind] = (byType[kind] || 0) + 1; bytes += Number(entry.transferSize || 0); });
    const nav = performance.getEntriesByType?.("navigation")?.[0];
    return { total: entries.length, byType, transferBytes: bytes, navigation: nav ? { type: nav.type || "", domInteractiveMs: Math.round(nav.domInteractive || 0), domCompleteMs: Math.round(nav.domComplete || 0), loadEndMs: Math.round(nav.loadEventEnd || 0) } : null };
  };

  const installObserver = () => {
    if (state.observerInstalled || !document.documentElement || typeof MutationObserver !== "function") return;
    state.observerInstalled = true;
    const observer = new MutationObserver((records) => {
      state.mutations.total += records.length; state.mutations.lastAt = Date.now();
      records.forEach((record) => {
        if (record.type === "attributes") state.mutations.attributes += 1;
        if (record.type === "childList") {
          state.mutations.added += record.addedNodes?.length || 0; state.mutations.removed += record.removedNodes?.length || 0;
          Array.from(record.addedNodes || []).forEach((node) => { if (node?.tagName) state.mutations.tags[node.tagName] = (state.mutations.tags[node.tagName] || 0) + 1; });
        }
      });
    });
    observer.observe(document.documentElement, { subtree: true, childList: true, attributes: true });
    state.observer = observer;
  };

  const pushError = (kind, value) => {
    state.errors.push({ kind, message: safe(value, 300), at: Date.now() });
    if (state.errors.length > 12) state.errors.splice(0, state.errors.length - 12);
  };

  const installErrors = () => {
    if (state.errorsInstalled) return;
    state.errorsInstalled = true;
    addEventListener("error", (event) => pushError("error", event?.message || event?.error?.message || "window-error"));
    addEventListener("unhandledrejection", (event) => pushError("rejection", event?.reason?.message || event?.reason || "unhandled-rejection"));
  };

  installObserver(); installErrors();

  api.diagnosticSnapshot = (phase) => {
    installObserver(); installErrors();
    const inputs = all(cfg.inputSelectors);
    const sendCandidates = all(cfg.sendProbeSelectors || cfg.sendSelectors);
    const usableSends = all(cfg.sendSelectors);
    const stops = all(cfg.stopSelectors), responses = all(cfg.responseSelectors), turns = all(cfg.turnSelectors);
    return {
      schema: 4, phase: safe(phase, 80), at: Date.now(),
      page: {
        origin: location.origin, path: location.pathname, readyState: document.readyState, visibility: document.visibilityState,
        hasFocus: document.hasFocus?.() || false, online: navigator.onLine, titleChars: String(document.title || "").length,
        bodyChars: String(document.body?.innerText || "").length, forms: document.forms?.length || 0,
        dialogs: document.querySelectorAll("[role='dialog'],dialog").length, menus: document.querySelectorAll("[role='menu'],[role='listbox']").length,
        iframes: document.querySelectorAll("iframe").length, serviceWorkerControlled: !!navigator.serviceWorker?.controller
      },
      viewport: { width: innerWidth, height: innerHeight, dpr: devicePixelRatio, scrollY: Math.round(scrollY || 0) },
      activeElement: summary(document.activeElement),
      groups: {
        inputs: group(cfg.inputSelectors),
        sendCandidate: group(cfg.sendProbeSelectors || cfg.sendSelectors),
        sendUsable: group(cfg.sendSelectors),
        stop: group(cfg.stopSelectors), responses: group(cfg.responseSelectors),
        turns: group(cfg.turnSelectors), assistants: group(cfg.assistantMarkerSelectors), newChat: group(cfg.newChatSelectors),
        attachments: group(cfg.attachmentButtonSelectors), model: group(cfg.modelSelectors), search: group(cfg.searchSelectors),
        reasoning: group(cfg.reasoningSelectors), tools: group(cfg.toolsSelectors)
      },
      primary: {
        input: summary(inputs.find(visible) || inputs[0]), inputAncestors: ancestors(inputs.find(visible) || inputs[0], 4),
        sendCandidate: summary(sendCandidates.find(visible) || sendCandidates[0]),
        sendUsable: summary(usableSends.find(usable) || usableSends.find(visible) || usableSends[0]),
        sendCandidateAncestors: ancestors(sendCandidates.find(visible) || sendCandidates[0], 4),
        stop: summary(stops.find(visible) || stops[0]), response: summary(responses.slice(-1)[0]),
        responseAncestors: ancestors(responses.slice(-1)[0], 3), turn: summary(turns.slice(-1)[0])
      },
      fileInputs: fileInputs(), interactive: interactives(), messages: messageStructures(),
      attachment: typeof api.attachmentProbe === "function" ? api.attachmentProbe() : null,
      submission: typeof api.submissionStatus === "function" ? api.submissionStatus() : null,
      mutations: {
        total: state.mutations.total, added: state.mutations.added, removed: state.mutations.removed, attributes: state.mutations.attributes,
        lastAgoMs: state.mutations.lastAt ? Math.max(0, Date.now() - state.mutations.lastAt) : -1,
        topAddedTags: Object.entries(state.mutations.tags).sort((a, b) => b[1] - a[1]).slice(0, 12)
      },
      jsErrors: state.errors.slice(-12), resources: resources()
    };
  };
})();
