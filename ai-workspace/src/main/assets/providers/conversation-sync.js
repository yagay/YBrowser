(() => {
  const api = window.__AIHUB__ || (window.__AIHUB__ = {});
  const cfg = window.__AIHUB_CONFIG__ || {};

  const all = (selectors, root = document) => {
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

  const domSort = (nodes) => nodes.slice().sort((a, b) => {
    if (a === b) return 0;
    const relation = a.compareDocumentPosition?.(b) || 0;
    if (relation & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
    if (relation & Node.DOCUMENT_POSITION_PRECEDING) return 1;
    return 0;
  });

  const textOf = (node) =>
    (node?.innerText || node?.textContent || "")
      .replace(/\u00a0/g, " ")
      .replace(/\n{3,}/g, "\n\n")
      .trim();

  const cleanedText = (node) => {
    if (!node) return "";
    const clone = node.cloneNode(true);
    try {
      clone.querySelectorAll(
        [
          "button",
          "nav",
          "textarea",
          "input",
          "[role='toolbar']",
          "[role='menu']",
          "[data-testid*='action']",
          "[class*='action']",
          "[aria-label*='copy' i]",
          "[aria-label*='edit' i]",
          "mat-icon"
        ].join(",")
      ).forEach((it) => it.remove());
    } catch (_) {}
    return textOf(clone);
  };

  const matchesAny = (node, selectors) => {
    if (!node?.matches) return false;
    return (selectors || []).some((selector) => {
      try { return node.matches(selector); } catch (_) { return false; }
    });
  };

  const roleOf = (node) => {
    if (!node) return "";
    const values = [
      node.getAttribute?.("data-message-author-role"),
      node.getAttribute?.("data-turn"),
      node.getAttribute?.("data-role"),
      node.getAttribute?.("data-author"),
      node.getAttribute?.("data-testid"),
      node.tagName,
      typeof node.className === "string" ? node.className : ""
    ].filter(Boolean).join(" ").toLowerCase();

    if (matchesAny(node, cfg.userTurnSelectors || [])) return "user";
    if (matchesAny(node, cfg.assistantTurnSelectors || [])) return "assistant";

    if (
      /(^|[^a-z])(user|human|query|prompt)([^a-z]|$)/.test(values) ||
      values.includes("user-query")
    ) return "user";

    if (
      /(^|[^a-z])(assistant|model|bot|agent)([^a-z]|$)/.test(values) ||
      values.includes("model-response") ||
      values.includes("claude-message") ||
      values.includes("font-claude-message")
    ) return "assistant";

    return "";
  };

  const contentFor = (node, role) => {
    const selectors = role === "user"
      ? (cfg.userContentSelectors || [])
      : (cfg.responseContentSelectors || []);

    const candidates = domSort(all(selectors, node));
    let best = "";
    candidates.forEach((candidate) => {
      const value = cleanedText(candidate);
      if (value.length > best.length) best = value;
    });
    return best || cleanedText(node);
  };

  const simpleHash = (value) => {
    let hash = 2166136261;
    const source = String(value || "");
    for (let i = 0; i < source.length; i++) {
      hash ^= source.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };

  const candidateSelectors = [
    ...(cfg.turnSelectors || []),
    ...(cfg.userTurnSelectors || []),
    ...(cfg.assistantTurnSelectors || []),
    "[data-message-author-role]",
    "[data-turn]",
    "[data-role='user']",
    "[data-role='assistant']",
    "[data-author='user']",
    "[data-author='assistant']",
    "user-query",
    "user-query-container",
    "query-text",
    "model-response",
    ".font-user-message",
    ".font-claude-message",
    "[data-testid*='user-message' i]",
    "[data-testid*='assistant' i]"
  ];

  const conversationSnapshot = () => {
    const candidates = domSort(all(candidateSelectors));
    const raw = [];
    const seen = new Set();

    candidates.forEach((node) => {
      const role = roleOf(node);
      if (!role) return;

      const text = contentFor(node, role);
      if (!text) return;

      const normalized = text.replace(/\s+/g, " ").trim();
      const dedupe = role + "|" + normalized;
      if (seen.has(dedupe)) return;
      seen.add(dedupe);

      raw.push({
        role,
        text: text.slice(0, 100000)
      });
    });

    // Nested provider markup can expose both a turn root and its content child.
    // Remove adjacent same-role entries when one is just a textual subset of the other.
    const compact = [];
    raw.forEach((message) => {
      const previous = compact[compact.length - 1];
      if (previous && previous.role === message.role) {
        const a = previous.text.replace(/\s+/g, " ").trim();
        const b = message.text.replace(/\s+/g, " ").trim();
        if (a === b) return;
        if (a.length >= b.length && a.includes(b)) return;
        if (b.length > a.length && b.includes(a)) {
          compact[compact.length - 1] = message;
          return;
        }
      }
      compact.push(message);
    });

    const messages = compact.slice(-200).map((message, index) => ({
      id: simpleHash(
        location.pathname + "|" + message.role + "|" + index + "|" + message.text
      ),
      role: message.role,
      text: message.text
    }));

    return {
      url: location.href,
      title: document.title || "",
      path: location.pathname,
      candidateCount: candidates.length,
      messages
    };
  };

  window.__AIHUB_CONVERSATION_READER__ = conversationSnapshot;
  api.conversationSnapshot = conversationSnapshot;
})();
