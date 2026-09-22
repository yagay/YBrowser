(() => {
  const cfg = window.__AIHUB_CONFIG__ || {};
  const sendState = window.__AIHUB_SEND_STATE__ || (window.__AIHUB_SEND_STATE__ = {});
  const attachmentState = window.__AIHUB_ATTACHMENT_STATE__ || (window.__AIHUB_ATTACHMENT_STATE__ = {});

  const simpleHash = (value) => {
    let hash = 2166136261;
    const source = String(value || "");
    for (let i = 0; i < source.length; i++) {
      hash ^= source.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };

  const all = (selectors) => {
    const seen = new Set();
    const out = [];
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

  const isVisible = (node) => {
    if (!node) return false;
    const rect = node.getBoundingClientRect();
    const style = getComputedStyle(node);
    return rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden";
  };

  const firstVisible = (selectors) => all(selectors).find(isVisible) || null;
  const firstUsable = (selectors) => all(selectors).find((node) => {
    if (!isVisible(node)) return false;
    if (node.disabled) return false;
    if (node.getAttribute?.("aria-disabled") === "true") return false;
    if (node.classList?.contains("ds-button--disabled")) return false;
    return true;
  }) || null;

  const sendProbeSelectors = () => cfg.sendProbeSelectors || cfg.sendSelectors || [];
  const recentAttachment = () => {
    const at = Number(attachmentState.lastAttachedAt || 0);
    const count = Number(attachmentState.lastAttachedCount || 0);
    return count > 0 && at > 0 && Date.now() - at < 45000;
  };

  const editorText = (element) => {
    if (!element) return "";
    if (element instanceof HTMLTextAreaElement || element instanceof HTMLInputElement) return element.value || "";
    return element.innerText || element.textContent || "";
  };

  const setEditorText = (element, text) => {
    element.focus();
    try { element.click(); } catch (_) {}

    if (element instanceof HTMLTextAreaElement || element instanceof HTMLInputElement) {
      const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const descriptor = Object.getOwnPropertyDescriptor(proto, "value");
      if (descriptor && descriptor.set) descriptor.set.call(element, text); else element.value = text;
      element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: text, composed: true }));
      element.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    }

    if (element.isContentEditable) {
      const selection = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(element);
      selection.removeAllRanges();
      selection.addRange(range);

      try {
        element.dispatchEvent(new InputEvent("beforeinput", {
          inputType: "deleteContentBackward", bubbles: true, cancelable: true, composed: true
        }));
        element.dispatchEvent(new InputEvent("beforeinput", {
          inputType: "insertText", data: text, bubbles: true, cancelable: true, composed: true
        }));
      } catch (_) {}

      let inserted = false;
      try {
        document.execCommand("delete", false);
        inserted = document.execCommand("insertText", false, text);
      } catch (_) {}
      if (!inserted && !(element.innerText || element.textContent || "").trim()) element.textContent = text;
      element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: text, composed: true }));
      element.dispatchEvent(new Event("change", { bubbles: true }));
      return true;
    }
    return false;
  };

  const pressEnter = (element) => {
    try { element.focus(); } catch (_) {}
    const opts = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
    element.dispatchEvent(new KeyboardEvent("keydown", opts));
    element.dispatchEvent(new KeyboardEvent("keypress", opts));
    element.dispatchEvent(new KeyboardEvent("keyup", opts));
  };

  const textOf = (node) => node ? (node.innerText || node.textContent || "").replace(/\u00a0/g, " ").trim() : "";

  const nodeSummary = (node) => {
    if (!node) return null;
    const rect = node.getBoundingClientRect();
    return {
      tag: node.tagName || "",
      id: node.id || "",
      role: node.getAttribute?.("role") || "",
      testid: node.getAttribute?.("data-testid") || "",
      author: node.getAttribute?.("data-message-author-role") || node.getAttribute?.("data-turn") || "",
      aria: node.getAttribute?.("aria-label") || "",
      ariaDisabled: node.getAttribute?.("aria-disabled") || "",
      disabled: !!node.disabled,
      classes: typeof node.className === "string" ? node.className.split(/\s+/).filter(Boolean).slice(0, 10) : [],
      width: Math.round(rect.width),
      height: Math.round(rect.height)
    };
  };

  const responseNodes = () => all(cfg.responseSelectors);
  const turnNodes = () => all(cfg.turnSelectors);

  const textFromTurn = (turn) => {
    if (!turn) return "";
    const contentSelectors = cfg.responseContentSelectors || [];
    for (const selector of contentSelectors) {
      try {
        const nodes = turn.querySelectorAll(selector);
        for (let i = nodes.length - 1; i >= 0; i--) {
          const value = textOf(nodes[i]);
          if (value) return value;
        }
      } catch (_) {}
    }

    const clone = turn.cloneNode(true);
    try {
      clone.querySelectorAll("button, nav, [role='toolbar'], [data-testid*='action'], [class*='action']").forEach((node) => node.remove());
    } catch (_) {}
    return textOf(clone);
  };

  const responseViaCopyButton = () => {
    const buttons = all(cfg.copyButtonSelectors);
    for (let i = buttons.length - 1; i >= 0; i--) {
      const button = buttons[i];
      let turn = null;
      for (const selector of (cfg.turnSelectors || [])) {
        try {
          turn = button.closest(selector);
          if (turn) break;
        } catch (_) {}
      }
      if (!turn) turn = button.closest("article, section, [data-testid*='conversation-turn']");
      const value = textFromTurn(turn);
      if (value) return value;
    }
    return "";
  };

  const extractResponse = () => {
    const nodes = responseNodes();
    for (let i = nodes.length - 1; i >= 0; i--) {
      const text = textOf(nodes[i]);
      if (text) return text;
    }
    return responseViaCopyButton();
  };

  const currentSubmissionStatus = () => {
    const editor = firstVisible(cfg.inputSelectors);
    const inputChars = editor ? editorText(editor).trim().length : 0;
    const responses = responseNodes().length;
    const turns = turnNodes().length;
    const rawGenerating = !!firstVisible(cfg.stopSelectors);
    const pathChanged = !!sendState.path && location.pathname !== sendState.path;
    const responseGrowth = responses > Number(sendState.responses || 0);
    const turnGrowth = turns > Number(sendState.turns || 0);
    const inputCleared = Number(sendState.submittedChars || 0) > 0 && inputChars === 0;
    const generating = cfg.ambiguousStopSelector ? (rawGenerating && (pathChanged || responseGrowth || turnGrowth)) : rawGenerating;
    const sendVisibleNode = firstVisible(sendProbeSelectors());
    const sendUsableNode = firstUsable(cfg.sendSelectors);
    return {
      acknowledged: !!(pathChanged || responseGrowth || turnGrowth || generating || inputCleared),
      pathChanged,
      responseGrowth,
      turnGrowth,
      generating,
      rawGenerating,
      inputCleared,
      inputChars,
      responses,
      turns,
      sendVisible: !!sendVisibleNode,
      sendUsable: !!sendUsableNode,
      sendCandidate: nodeSummary(sendVisibleNode),
      attachmentOnly: !!sendState.attachmentOnly,
      recentAttachment: recentAttachment(),
      lastAttachedCount: Number(attachmentState.lastAttachedCount || 0),
      lastAttachedAgeMs: attachmentState.lastAttachedAt ? Math.max(0, Date.now() - Number(attachmentState.lastAttachedAt)) : -1,
      elapsedMs: sendState.startedAt ? Date.now() - sendState.startedAt : -1,
      clickStrategy: sendState.clickStrategy || "",
      submittedChars: Number(sendState.submittedChars || 0)
    };
  };

  const sendResult = () => cfg.verifySubmission === false ? "ok" : "verify";

  window.__AIHUB__ = {
    isLoggedIn() {
      return !!firstVisible(cfg.inputSelectors) || !!firstVisible(cfg.loggedInSelectors);
    },

    send(text) {
      const editor = firstVisible(cfg.inputSelectors);
      if (!editor) return "no-input";

      const submittedText = String(text || "");
      const submittedChars = submittedText.trim().length;
      const attachmentOnly = submittedChars === 0 && recentAttachment();

      sendState.path = location.pathname;
      sendState.responses = responseNodes().length;
      sendState.turns = turnNodes().length;
      sendState.bodyChars = (document.body?.innerText || "").length;
      sendState.submittedChars = submittedChars;
      sendState.attachmentOnly = attachmentOnly;
      sendState.startedAt = Date.now();
      sendState.clickStrategy = "";

      if (submittedChars > 0) {
        if (!setEditorText(editor, submittedText)) return "input-failed";
      } else {
        try { editor.focus(); } catch (_) {}
      }

      const immediate = firstUsable(cfg.sendSelectors);
      if (immediate) {
        sendState.clickStrategy = "immediate-button";
        immediate.click();
        return sendResult();
      }

      let submitted = false;
      sendState.clickStrategy = attachmentOnly ? "waiting-attachment-button" : "delayed-search";
      const delays = attachmentOnly
        ? [120, 350, 700, 1200, 2000, 3500, 5500, 8000, 12000]
        : [120, 280, 520, 900, 1500, 2500];

      delays.forEach((delay, index) => {
        setTimeout(() => {
          if (submitted) return;
          const delayed = firstUsable(cfg.sendSelectors);
          if (delayed) {
            submitted = true;
            sendState.clickStrategy = `delayed-button-${delay}`;
            delayed.click();
          } else if (index === delays.length - 1) {
            submitted = true;
            if (submittedChars > 0) {
              sendState.clickStrategy = "enter-fallback";
              pressEnter(editor);
            } else {
              sendState.clickStrategy = "attachment-button-timeout";
            }
          }
        }, delay);
      });
      return sendResult();
    },

    submissionAcknowledged() {
      return currentSubmissionStatus().acknowledged;
    },

    submissionStatus() {
      return currentSubmissionStatus();
    },

    extractLastResponse() {
      return extractResponse();
    },

    isGenerating() {
      const raw = !!firstVisible(cfg.stopSelectors);
      if (!cfg.ambiguousStopSelector) return raw;
      if (!raw) return false;
      return location.pathname !== (cfg.homePath || "/") && (turnNodes().length > 0 || responseNodes().length > 0);
    },

    stop() {
      const stop = firstUsable(cfg.stopSelectors);
      if (!stop) return "not-generating";
      stop.click();
      return "ok";
    },

    newChat() {
      const button = firstUsable(cfg.newChatSelectors) || firstVisible(cfg.newChatSelectors);
      if (button) { button.click(); return "ok"; }
      if (cfg.homeUrl) { location.href = cfg.homeUrl; return "navigating"; }
      return "not-found";
    },

    probeSummary() {
      const responses = responseNodes();
      const extracted = extractResponse();
      const turns = turnNodes();
      const assistants = all(cfg.assistantMarkerSelectors);
      const copyButtons = all(cfg.copyButtonSelectors);
      const editor = firstVisible(cfg.inputSelectors);
      const sendCandidates = all(sendProbeSelectors());
      return {
        viewport: { width: innerWidth, height: innerHeight, dpr: devicePixelRatio },
        inputCount: all(cfg.inputSelectors).length,
        inputChars: editor ? editorText(editor).trim().length : 0,
        visibleInput: nodeSummary(editor),
        loggedInMarkerCount: all(cfg.loggedInSelectors).length,
        sendCount: all(cfg.sendSelectors).length,
        sendCandidateCount: sendCandidates.length,
        visibleSend: nodeSummary(firstVisible(cfg.sendSelectors)),
        visibleSendCandidate: nodeSummary(sendCandidates.find(isVisible) || null),
        usableSend: nodeSummary(firstUsable(cfg.sendSelectors)),
        responseCount: responses.length,
        extractedChars: extracted.length,
        turnCount: turns.length,
        assistantMarkerCount: assistants.length,
        copyButtonCount: copyButtons.length,
        lastTurn: nodeSummary(turns.length ? turns[turns.length - 1] : null),
        stopCount: all(cfg.stopSelectors).length,
        pathHash: simpleHash(location.pathname),
        bodyChars: (document.body?.innerText || "").length,
        submission: currentSubmissionStatus()
      };
    }
  };
})();
