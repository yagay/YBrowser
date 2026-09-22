(() => {
  const api = window.__AIHUB__;
  const cfg = window.__AIHUB_CONFIG__ || {};
  if (!api) return;

  const runtime = window.__AIHUB_RESPONSE_RUNTIME__ || (window.__AIHUB_RESPONSE_RUNTIME__ = {
    lastKey: "",
    lastText: "",
    lastTextChangeAt: 0,
    lastObservedAt: 0
  });

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

  const domSort = (nodes) => nodes.slice().sort((a, b) => {
    if (a === b) return 0;
    const relation = a.compareDocumentPosition?.(b) || 0;
    if (relation & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
    if (relation & Node.DOCUMENT_POSITION_PRECEDING) return 1;
    return 0;
  });

  const textOf = (node) => (node?.innerText || node?.textContent || "").replace(/\u00a0/g, " ").trim();

  const simpleHash = (value) => {
    let hash = 2166136261;
    const source = String(value || "");
    for (let i = 0; i < source.length; i++) {
      hash ^= source.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };

  const nodePath = (node) => {
    if (!node) return "";
    const direct = node.id || node.getAttribute?.("data-message-id") || node.getAttribute?.("data-testid") || node.getAttribute?.("data-test-id");
    if (direct) return `${node.tagName || "NODE"}#${direct}`;
    const parts = [];
    let current = node;
    while (current?.parentElement && parts.length < 7) {
      const parent = current.parentElement;
      const sameTag = Array.from(parent.children).filter((it) => it.tagName === current.tagName);
      const index = Math.max(0, sameTag.indexOf(current));
      const cls = typeof current.className === "string"
        ? current.className.split(/\s+/).filter(Boolean).slice(0, 2).join(".")
        : "";
      parts.push(`${String(current.tagName || "node").toLowerCase()}${cls ? "." + cls : ""}:nth(${index})`);
      current = parent;
    }
    return parts.reverse().join(">");
  };

  const cleanedText = (node) => {
    if (!node) return "";
    const clone = node.cloneNode(true);
    try {
      clone.querySelectorAll(
        "button, nav, [role='toolbar'], [role='menu'], [data-testid*='action'], [class*='action'], [aria-label*='copy' i]"
      ).forEach((it) => it.remove());
    } catch (_) {}
    return textOf(clone);
  };

  const allWithin = (root, selectors) => {
    const out = [];
    const seen = new Set();
    (selectors || []).forEach((selector) => {
      try {
        root.querySelectorAll(selector).forEach((node) => {
          if (!seen.has(node)) {
            seen.add(node);
            out.push(node);
          }
        });
      } catch (_) {}
    });
    return out;
  };

  const contentFromTurn = (turn) => {
    if (!turn) return "";
    const candidates = domSort(allWithin(turn, cfg.responseContentSelectors || []));
    for (let i = candidates.length - 1; i >= 0; i--) {
      const value = cleanedText(candidates[i]);
      if (value) return value;
    }
    return cleanedText(turn);
  };

  const turnViaCopyButton = () => {
    const buttons = domSort(all(cfg.copyButtonSelectors || []).filter(visible));
    const button = buttons.length ? buttons[buttons.length - 1] : null;
    if (!button) return null;

    for (const selector of (cfg.turnSelectors || [])) {
      try {
        const turn = button.closest(selector);
        if (turn) return turn;
      } catch (_) {}
    }

    let node = button.parentElement;
    let best = null;
    for (let depth = 0; node && depth < 9; depth++, node = node.parentElement) {
      const value = cleanedText(node);
      if (value.length >= 8 && value.length <= 50_000) {
        best = node;
        if (node.querySelector?.("[aria-label*='Copy response' i], [aria-label='Copy']")) break;
      }
    }
    return best;
  };

  const assistantTurns = () => {
    const explicit = domSort(all(cfg.assistantTurnSelectors || []));
    if (explicit.length) return explicit;

    const turns = domSort(all(cfg.turnSelectors || []));
    if (cfg.responseStrategy === "last-turn" && turns.length) return [turns[turns.length - 1]];

    const copyTurn = turnViaCopyButton();
    if (copyTurn) return [copyTurn];
    return [];
  };

  const responseCandidate = () => {
    const turns = assistantTurns();
    for (let i = turns.length - 1; i >= 0; i--) {
      const value = contentFromTurn(turns[i]);
      if (value) return { node: turns[i], text: value, source: "assistant-turn" };
      if (cfg.responseStrategy === "last-turn") return { node: turns[i], text: "", source: "assistant-turn-empty" };
    }

    const nodes = domSort(all(cfg.responseSelectors || []));
    for (let i = nodes.length - 1; i >= 0; i--) {
      const value = cleanedText(nodes[i]);
      if (value) return { node: nodes[i], text: value, source: "response-selector" };
    }

    const copyTurn = turnViaCopyButton();
    if (copyTurn) return { node: copyTurn, text: cleanedText(copyTurn), source: "copy-ancestor" };
    return { node: null, text: "", source: "none" };
  };

  const snapshot = () => {
    const candidate = responseCandidate();
    const responseCount = all(cfg.responseSelectors || []).length;
    const turnCount = all(cfg.turnSelectors || []).length;
    const keyBase = candidate.node ? nodePath(candidate.node) : "";
    const key = simpleHash(`${location.pathname}|${keyBase || "none"}`);
    const now = Date.now();
    if (candidate.text !== runtime.lastText || key !== runtime.lastKey) {
      runtime.lastText = candidate.text;
      runtime.lastKey = key;
      runtime.lastTextChangeAt = now;
    }
    runtime.lastObservedAt = now;
    return {
      text: candidate.text,
      key,
      source: candidate.source,
      responseCount,
      turnCount,
      textChars: candidate.text.length,
      pathHash: simpleHash(location.pathname),
      quietMs: runtime.lastTextChangeAt ? Math.max(0, now - runtime.lastTextChangeAt) : -1
    };
  };

  const rawStopVisible = () => all(cfg.stopSelectors || []).some(visible);

  const generationState = () => {
    const snap = snapshot();
    const sendState = window.__AIHUB_SEND_STATE__ || {};
    const submission = typeof api.submissionStatus === "function" ? api.submissionStatus() : null;

    if (submission?.clickStrategy === "attachment-button-timeout") {
      return { state: "error", reason: "attachment-send-timeout", ...snap };
    }
    if (submission && !submission.acknowledged && submission.attachmentOnly) {
      return { state: "queued", reason: submission.clickStrategy || "attachment-queued", ...snap };
    }

    if (cfg.generationStrategy === "quiet-last-turn") {
      const baselineTurns = Number(sendState.turns || 0);
      const hasNewTurn = snap.turnCount > baselineTurns;
      if (hasNewTurn && !snap.text) return { state: "generating", reason: "new-empty-assistant-turn", ...snap };
      if (hasNewTurn && snap.text && snap.quietMs >= 0 && snap.quietMs < Number(cfg.generationQuietMs || 1800)) {
        return { state: "generating", reason: "assistant-turn-changing", ...snap };
      }
      if (hasNewTurn && snap.text) return { state: "idle", reason: "assistant-turn-stable", ...snap };
      return { state: "idle", reason: "no-new-assistant-turn", ...snap };
    }

    if (rawStopVisible()) return { state: "generating", reason: "stop-control-visible", ...snap };
    return { state: "idle", reason: "no-stop-control", ...snap };
  };

  api.extractLastResponse = () => snapshot().text;
  api.responseSnapshot = () => snapshot();
  api.generationState = () => generationState();
  api.isGenerating = () => generationState().state === "generating";
})();
