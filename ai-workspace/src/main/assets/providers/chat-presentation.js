(() => {
  const ROOT_ATTR = "data-aihub-chat-mode";
  const HIDDEN_ATTR = "data-aihub-chat-hidden";
  const COMPOSER_ATTR = "data-aihub-chat-composer";
  const STYLE_ID = "aihub-chat-presentation-style";
  const ARCHIVE_ID = "aihub-local-history";
  const VERSION = 2;

  const existing = window.__AIHUB_CHAT_PRESENTATION__;
  if (existing && existing.version >= VERSION) return;

  const state = window.__AIHUB_CHAT_PRESENTATION_STATE__ ||
    (window.__AIHUB_CHAT_PRESENTATION_STATE__ = {
      enabled: false,
      observer: null,
      timer: 0,
      applying: false,
      scroll: [],
      archive: [],
      archiveSignature: ""
    });

  if (!Array.isArray(state.archive)) state.archive = [];
  if (typeof state.archiveSignature !== "string") {
    state.archiveSignature = "";
  }

  const cfg = () => window.__AIHUB_CONFIG__ || {};

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

  const insideTurn = (node) => {
    if (!node?.closest) return false;
    const selectors = cfg().turnSelectors || [];
    return selectors.some((selector) => {
      try { return !!node.closest(selector); } catch (_) { return false; }
    });
  };

  const rememberScroll = () => {
    const saved = [];
    const add = (node) => {
      if (!node || saved.some((it) => it.node === node)) return;
      const top = Number(node.scrollTop || 0);
      if (top > 0 || node === document.scrollingElement) {
        saved.push({ node, top });
      }
    };

    add(document.scrollingElement);
    try {
      document.querySelectorAll("*").forEach((node) => {
        if (saved.length >= 12) return;
        const style = getComputedStyle(node);
        if (!/(auto|scroll|overlay)/i.test(style.overflowY || "")) return;
        if (node.scrollHeight <= node.clientHeight + 80) return;
        if (node.scrollTop > 0) add(node);
      });
    } catch (_) {}
    state.scroll = saved;
  };

  const restoreScroll = () => {
    const saved = state.scroll.slice();
    const run = () => saved.forEach(({ node, top }) => {
      try { node.scrollTop = top; } catch (_) {}
    });
    try { requestAnimationFrame(() => requestAnimationFrame(run)); } catch (_) { run(); }
  };

  const ensureStyle = () => {
    let style = document.getElementById(STYLE_ID);
    if (!style) {
      style = document.createElement("style");
      style.id = STYLE_ID;
      (document.head || document.documentElement).appendChild(style);
    }
    style.textContent = `
      html[${ROOT_ATTR}="true"] [${HIDDEN_ATTR}="true"] {
        display: none !important;
      }

      /*
       * Keep ChatGPT's real composer alive for AIHub's automation layer while
       * moving it completely outside the visible viewport. common.js checks
       * geometry/display state before sending, so display:none would break
       * native AIHub sending and attachment submission.
       */
      html[${ROOT_ATTR}="true"] [${COMPOSER_ATTR}="true"] {
        position: fixed !important;
        left: 0 !important;
        top: calc(100vh + 320px) !important;
        width: min(760px, 96vw) !important;
        max-height: 360px !important;
        opacity: 0.001 !important;
        pointer-events: none !important;
        z-index: -2147483000 !important;
      }

      html[${ROOT_ATTR}="true"] #thread-bottom-container,
      html[${ROOT_ATTR}="true"] [data-testid="composer-root"] {
        position: fixed !important;
        left: 0 !important;
        top: calc(100vh + 320px) !important;
        width: min(760px, 96vw) !important;
        max-height: 360px !important;
        opacity: 0.001 !important;
        pointer-events: none !important;
        z-index: -2147483000 !important;
      }

      html[${ROOT_ATTR}="true"] #${ARCHIVE_ID} {
        width: 100% !important;
        max-width: 48rem !important;
        margin: 0 auto !important;
        padding: 12px 16px 8px !important;
        box-sizing: border-box !important;
        color: inherit !important;
        font: inherit !important;
      }

      html[${ROOT_ATTR}="true"] #${ARCHIVE_ID} .aihub-archive-label {
        padding: 8px 0 14px !important;
        text-align: center !important;
        font-size: 12px !important;
        opacity: 0.62 !important;
      }

      html[${ROOT_ATTR}="true"] #${ARCHIVE_ID} .aihub-archive-turn {
        display: flex !important;
        width: 100% !important;
        margin: 0 0 16px !important;
        box-sizing: border-box !important;
      }

      html[${ROOT_ATTR}="true"] #${ARCHIVE_ID} .aihub-archive-turn.user {
        justify-content: flex-end !important;
      }

      html[${ROOT_ATTR}="true"] #${ARCHIVE_ID} .aihub-archive-turn.assistant {
        justify-content: flex-start !important;
      }

      html[${ROOT_ATTR}="true"] #${ARCHIVE_ID} .aihub-archive-content {
        white-space: pre-wrap !important;
        overflow-wrap: anywhere !important;
        line-height: 1.6 !important;
        font: inherit !important;
        color: inherit !important;
      }

      html[${ROOT_ATTR}="true"] #${ARCHIVE_ID} .aihub-archive-turn.user .aihub-archive-content {
        max-width: 85% !important;
        padding: 10px 14px !important;
        border-radius: 18px !important;
        background: rgba(127, 127, 127, 0.14) !important;
      }

      html[${ROOT_ATTR}="true"] #${ARCHIVE_ID} .aihub-archive-turn.assistant .aihub-archive-content {
        width: 100% !important;
        padding: 2px 0 !important;
      }

      html[${ROOT_ATTR}="true"] body {
        overscroll-behavior-x: none !important;
      }
    `;
  };

  const firstRealTurn = () =>
    domSort(all(cfg().turnSelectors || []))
      .find((node) => !node.closest?.("#" + ARCHIVE_ID)) || null;

  const scrollRootFor = (node) => {
    let current = node?.parentElement || null;
    for (let depth = 0; current && depth < 14; depth++, current = current.parentElement) {
      try {
        const style = getComputedStyle(current);
        if (
          /(auto|scroll|overlay)/i.test(style.overflowY || "") &&
          current.scrollHeight > current.clientHeight + 80
        ) {
          return current;
        }
      } catch (_) {}
    }
    return document.scrollingElement || document.documentElement;
  };

  const archiveSignature = (items) =>
    JSON.stringify((items || []).map((item) => [
      String(item?.role || ""),
      String(item?.text || "")
    ]));

  const renderArchive = () => {
    const existingArchive = document.getElementById(ARCHIVE_ID);

    if (!state.enabled || !state.archive.length) {
      existingArchive?.remove();
      return;
    }

    const firstTurn = firstRealTurn();
    if (!firstTurn?.parentElement) {
      existingArchive?.remove();
      return;
    }

    const signature = archiveSignature(state.archive);
    if (
      existingArchive &&
      existingArchive.dataset.signature === signature &&
      existingArchive.nextElementSibling === firstTurn
    ) {
      return;
    }

    const root = scrollRootFor(firstTurn);
    const beforeTop = firstTurn.getBoundingClientRect().top;

    existingArchive?.remove();

    const container = document.createElement("div");
    container.id = ARCHIVE_ID;
    container.dataset.signature = signature;
    container.setAttribute("data-aihub-local-history", "true");

    const label = document.createElement("div");
    label.className = "aihub-archive-label";
    label.textContent = "本地保存的更早历史";
    container.appendChild(label);

    state.archive.forEach((item) => {
      const role = item?.role === "user" ? "user" : "assistant";
      const turn = document.createElement("div");
      turn.className = "aihub-archive-turn " + role;
      turn.setAttribute("data-aihub-archive-role", role);

      const content = document.createElement("div");
      content.className = "aihub-archive-content";
      content.textContent = String(item?.text || "");
      turn.appendChild(content);
      container.appendChild(turn);
    });

    firstTurn.parentElement.insertBefore(container, firstTurn);

    const restoreAnchor = () => {
      try {
        const afterTop = firstTurn.getBoundingClientRect().top;
        const delta = afterTop - beforeTop;
        if (Math.abs(delta) > 0.5) {
          root.scrollTop = Number(root.scrollTop || 0) + delta;
        }
      } catch (_) {}
    };

    try {
      requestAnimationFrame(() => requestAnimationFrame(restoreAnchor));
    } catch (_) {
      restoreAnchor();
    }
  };

  const setArchivedMessages = (items) => {
    state.archive = Array.isArray(items)
      ? items
          .filter((item) =>
            item &&
            (item.role === "user" || item.role === "assistant") &&
            String(item.text || "").trim()
          )
          .map((item) => ({
            role: item.role,
            text: String(item.text || "")
          }))
      : [];
    state.archiveSignature = archiveSignature(state.archive);
    renderArchive();
    return String(state.archive.length);
  };

  const markComposer = () => {
    const inputs = all(cfg().inputSelectors || []);
    inputs.forEach((input) => {
      let target = null;
      try { target = input.closest("form"); } catch (_) {}

      if (!target) {
        let node = input.parentElement;
        for (let depth = 0; node && depth < 8; depth++, node = node.parentElement) {
          const rect = node.getBoundingClientRect?.();
          if (!rect) continue;
          const classText =
            (typeof node.className === "string" ? node.className : "").toLowerCase();
          const idText = String(node.id || "").toLowerCase();
          const nearBottom = rect.bottom >= innerHeight - 220;
          const compactHeight = rect.height > 0 && rect.height < Math.min(420, innerHeight * 0.5);
          if (
            compactHeight &&
            (
              classText.includes("composer") ||
              idText.includes("composer") ||
              nearBottom
            )
          ) {
            target = node;
          }
        }
      }

      (target || input.parentElement || input)
        ?.setAttribute?.(COMPOSER_ATTR, "true");
    });
  };

  const markChrome = () => {
    const candidates = [
      ...all([
        "aside",
        "#history",
        "[data-testid*='sidebar' i]",
        "[id*='sidebar' i]",
        "button[aria-label*='sidebar' i]",
        "button[aria-label*='open sidebar' i]",
        "button[aria-label*='close sidebar' i]",
        "a[data-testid='create-new-chat-button']"
      ])
    ];

    try {
      document.querySelectorAll("nav").forEach((node) => {
        if (insideTurn(node)) return;
        const rect = node.getBoundingClientRect();
        const aria = String(node.getAttribute("aria-label") || "").toLowerCase();
        const sideRail =
          rect.height > innerHeight * 0.35 &&
          rect.width > 0 &&
          rect.width < Math.min(460, innerWidth * 0.55);
        if (
          sideRail ||
          aria.includes("history") ||
          aria.includes("sidebar") ||
          aria.includes("navigation")
        ) {
          candidates.push(node);
        }
      });
    } catch (_) {}

    try {
      document.querySelectorAll("header").forEach((node) => {
        if (insideTurn(node)) return;
        const rect = node.getBoundingClientRect();
        const style = getComputedStyle(node);
        const topChrome =
          rect.height > 0 &&
          rect.height < 180 &&
          rect.top < 120 &&
          (
            style.position === "fixed" ||
            style.position === "sticky" ||
            node.parentElement === document.body ||
            node.closest("main") == null
          );
        if (topChrome) candidates.push(node);
      });
    } catch (_) {}

    candidates.forEach((node) => {
      if (!node || insideTurn(node)) return;
      if (node.hasAttribute(COMPOSER_ATTR)) return;
      node.setAttribute(HIDDEN_ATTR, "true");
    });
  };

  const applyMarks = () => {
    if (!state.enabled || state.applying) return;
    state.applying = true;
    try {
      ensureStyle();
      document.documentElement?.setAttribute(ROOT_ATTR, "true");
      markComposer();
      markChrome();
      renderArchive();
    } finally {
      state.applying = false;
    }
  };

  const schedule = () => {
    if (!state.enabled) return;
    clearTimeout(state.timer);
    state.timer = setTimeout(applyMarks, 90);
  };

  const enableObserver = () => {
    if (state.observer) return;
    state.observer = new MutationObserver(schedule);
    state.observer.observe(
      document.documentElement || document.body,
      { subtree: true, childList: true }
    );
  };

  const disableObserver = () => {
    clearTimeout(state.timer);
    state.timer = 0;
    if (state.observer) {
      state.observer.disconnect();
      state.observer = null;
    }
  };

  const clearPresentation = () => {
    disableObserver();
    document.documentElement?.removeAttribute(ROOT_ATTR);
    document.getElementById(STYLE_ID)?.remove();
    document.getElementById(ARCHIVE_ID)?.remove();
    try {
      document.querySelectorAll(
        `[${HIDDEN_ATTR}], [${COMPOSER_ATTR}]`
      ).forEach((node) => {
        node.removeAttribute(HIDDEN_ATTR);
        node.removeAttribute(COMPOSER_ATTR);
      });
    } catch (_) {}
  };

  const setEnabled = (enabled) => {
    const next = !!enabled;
    rememberScroll();
    state.enabled = next;

    if (next) {
      ensureStyle();
      applyMarks();
      enableObserver();
    } else {
      clearPresentation();
    }

    restoreScroll();
    return next ? "enabled" : "disabled";
  };

  window.__AIHUB_CHAT_PRESENTATION__ = {
    version: VERSION,
    setEnabled,
    refresh() {
      applyMarks();
      return state.enabled ? "enabled" : "disabled";
    },
    isEnabled() {
      return !!state.enabled;
    },
    setArchivedMessages(items) {
      return setArchivedMessages(items);
    }
  };
})();