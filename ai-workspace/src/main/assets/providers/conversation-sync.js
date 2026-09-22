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

  const sanitizeVisibleText = (value) =>
    String(value || "")
      .replace(/\uE200[\s\S]*?\uE201/g, "")
      .replace(/[\uE000-\uF8FF]/g, "")
      .replace(/\u00a0/g, " ")
      .replace(/\n{3,}/g, "\n\n")
      .trim();

  const textOf = (node) =>
    sanitizeVisibleText(node?.innerText || node?.textContent || "");

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

  const cache = window.__AIHUB_CONVERSATION_CACHE__ ||
    (window.__AIHUB_CONVERSATION_CACHE__ = {
      path: location.pathname,
      messages: [],
      lastScrollTop: null,
      version: 0
    });

  const messageKey = (message) =>
    message.role + "|" + String(message.text || "").replace(/\s+/g, " ").trim();

  const resetCacheIfNeeded = () => {
    if (cache.path === location.pathname) return;
    cache.path = location.pathname;
    cache.messages = [];
    cache.lastScrollTop = null;
    cache.version = Number(cache.version || 0) + 1;
  };

  const readVisibleConversation = () => {
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
      raw.push({ role, text });
    });

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

    return { candidates, messages: compact };
  };

  const overlap = (left, right) => {
    const max = Math.min(left.length, right.length);
    for (let size = max; size > 0; size--) {
      let same = true;
      for (let i = 0; i < size; i++) {
        if (messageKey(left[left.length - size + i]) !== messageKey(right[i])) {
          same = false;
          break;
        }
      }
      if (same) return size;
    }
    return 0;
  };

  const containsSequence = (haystack, needle) => {
    if (!needle.length || needle.length > haystack.length) return false;
    outer: for (let i = 0; i <= haystack.length - needle.length; i++) {
      for (let j = 0; j < needle.length; j++) {
        if (messageKey(haystack[i + j]) !== messageKey(needle[j])) continue outer;
      }
      return true;
    }
    return false;
  };

  const scrollMetrics = (node) => {
    const root = node || document.scrollingElement || document.documentElement;
    return {
      top: Number(root.scrollTop || window.scrollY || 0),
      height: Number(root.scrollHeight || document.documentElement.scrollHeight || 0),
      client: Number(root.clientHeight || innerHeight || 0)
    };
  };

  const findScrollRoot = () => {
    const visible = readVisibleConversation();
    const scores = new Map();

    const consider = (node, weight) => {
      if (!node) return;
      const metrics = scrollMetrics(node);
      if (metrics.height <= metrics.client + 160 || metrics.client < 120) return;
      let overflow = "";
      try { overflow = getComputedStyle(node).overflowY || ""; } catch (_) {}
      const documentRoot =
        node === document.scrollingElement ||
        node === document.documentElement ||
        node === document.body;
      if (!documentRoot && !/(auto|scroll|overlay)/i.test(overflow)) return;
      const score = weight * 1000000 + metrics.height;
      if (score > (scores.get(node) || 0)) scores.set(node, score);
    };

    visible.candidates.slice(0, 12).forEach((turn) => {
      let node = turn.parentElement;
      let depth = 0;
      while (node && depth < 12) {
        consider(node, 20 - depth);
        node = node.parentElement;
        depth++;
      }
    });

    consider(document.scrollingElement, 2);
    consider(document.documentElement, 1);
    const ranked = Array.from(scores.entries()).sort((a, b) => b[1] - a[1]);
    return ranked.length
      ? ranked[0][0]
      : (document.scrollingElement || document.documentElement);
  };

  const emitCacheChanged = () => {
    cache.version = Number(cache.version || 0) + 1;
    try {
      window.dispatchEvent(new CustomEvent("aihub-conversation-updated", {
        detail: { version: cache.version, count: cache.messages.length }
      }));
    } catch (_) {}
  };

  const mergeVisible = (messages, direction) => {
    if (!messages.length) return false;
    if (!cache.messages.length) {
      cache.messages = messages.slice();
      return true;
    }
    if (containsSequence(cache.messages, messages)) return false;

    const appendOverlap = overlap(cache.messages, messages);
    const prependOverlap = overlap(messages, cache.messages);
    let merged;

    if (appendOverlap > prependOverlap) {
      merged = cache.messages.concat(messages.slice(appendOverlap));
    } else if (prependOverlap > appendOverlap) {
      merged = messages.concat(cache.messages.slice(prependOverlap));
    } else if (direction < 0) {
      merged = messages.concat(cache.messages);
    } else if (direction > 0) {
      merged = cache.messages.concat(messages);
    } else {
      const known = new Set(cache.messages.map(messageKey));
      const additions = messages.filter((message) => !known.has(messageKey(message)));
      if (!additions.length) return false;
      merged = cache.messages.concat(additions);
    }

    const unique = [];
    const seen = new Set();
    merged.forEach((message) => {
      const key = messageKey(message);
      if (seen.has(key)) return;
      seen.add(key);
      unique.push(message);
    });

    cache.messages = unique;
    return true;
  };

  const absorbVisible = () => {
    resetCacheIfNeeded();
    const root = findScrollRoot();
    const metrics = scrollMetrics(root);
    const direction =
      cache.lastScrollTop == null ? 0 :
      metrics.top < cache.lastScrollTop - 2 ? -1 :
      metrics.top > cache.lastScrollTop + 2 ? 1 : 0;
    cache.lastScrollTop = metrics.top;

    const visible = readVisibleConversation();
    if (mergeVisible(visible.messages, direction)) emitCacheChanged();

    return {
      candidateCount: visible.candidates.length,
      collected: cache.messages.length
    };
  };

  const cachedMessages = () => {
    const occurrences = new Map();
    return cache.messages.map((message) => {
      const base = messageKey(message);
      const occurrence = Number(occurrences.get(base) || 0);
      occurrences.set(base, occurrence + 1);
      return {
        id: simpleHash(location.pathname + "|" + base + "|" + occurrence),
        role: message.role,
        text: message.text
      };
    });
  };

  const conversationSnapshot = () => {
    const captured = absorbVisible();
    return {
      url: location.href,
      title: document.title || "",
      path: location.pathname,
      candidateCount: captured.candidateCount,
      source: "dom",
      complete: false,
      messages: cachedMessages(),
      capture: {
        passive: true,
        collected: captured.collected
      }
    };
  };

  // Kept only for ABI compatibility with older native code. It deliberately
  // does not scroll or trigger lazy loading.
  const startConversationHydration = () => {
    absorbVisible();
    return "disabled-passive-only";
  };

  if (!window.__AIHUB_PASSIVE_SCROLL_WATCHER__) {
    let timer = 0;
    const onScroll = () => {
      clearTimeout(timer);
      timer = setTimeout(() => {
        try { absorbVisible(); } catch (_) {}
      }, 120);
    };
    document.addEventListener("scroll", onScroll, true);
    window.__AIHUB_PASSIVE_SCROLL_WATCHER__ = { onScroll };
  }

  window.__AIHUB_CONVERSATION_READER__ = conversationSnapshot;
  api.conversationSnapshot = conversationSnapshot;
  api.startConversationHydration = startConversationHydration;
  window.__AIHUB_CONVERSATION_HYDRATE__ = startConversationHydration;
})();
