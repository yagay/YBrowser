(() => {
  const api = window.__AIHUB__;
  if (!api || typeof api.diagnosticSnapshot !== "function") return;
  const bridge = window.AIHubNativeFiles;
  const state = window.__AIHUB_DIAG_HOOK_STATE__ || (window.__AIHUB_DIAG_HOOK_STATE__ = {
    apiRef: null,
    wrapped: {},
    lastPageKey: "",
    ackPolls: 0,
    lastAck: false
  });

  if (state.apiRef !== api) {
    state.apiRef = api;
    state.wrapped = {};
  }

  const scope = () => {
    try { return `web:${location.hostname || "unknown"}`; } catch (_) { return "web:unknown"; }
  };

  const emit = (phase) => {
    try {
      if (!bridge || typeof bridge.diagnosticSnapshot !== "function") return;
      const payload = JSON.stringify(api.diagnosticSnapshot(phase));
      bridge.diagnosticSnapshot(scope(), phase, payload);
    } catch (error) {
      try { bridge?.diagnosticEvent?.(scope(), "snapshot-error", String(error)); } catch (_) {}
    }
  };

  const emitLater = (phase, delay) => setTimeout(() => emit(`${phase}+${delay}ms`), delay);

  const delaysFor = (name) => {
    if (name === "send") return [250, 1000, 3000, 6000, 9000, 12500];
    if (name === "attachStagedFiles" || name === "prepareAttachmentInput") return [250, 1000, 3000, 6000];
    return [250, 1000, 3000];
  };

  const wrap = (name) => {
    if (state.wrapped[name] || typeof api[name] !== "function") return;
    const original = api[name];
    state.wrapped[name] = true;
    api[name] = function(...args) {
      if (name === "send") {
        state.ackPolls = 0;
        state.lastAck = false;
      }
      emit(`${name}:before`);
      let result;
      try {
        result = original.apply(this, args);
      } catch (error) {
        try { bridge?.diagnosticEvent?.(scope(), `${name}:throw`, String(error)); } catch (_) {}
        emit(`${name}:throw`);
        throw error;
      }
      emit(`${name}:after`);
      delaysFor(name).forEach((delay) => emitLater(name, delay));
      return result;
    };
  };

  [
    "send", "attachStagedFiles", "prepareAttachmentInput", "openAttachmentPicker",
    "performAction", "openOptionPicker", "selectOption", "stop", "newChat"
  ].forEach((name) => wrap(name));

  if (!state.wrapped.submissionAcknowledged && typeof api.submissionAcknowledged === "function") {
    const originalAck = api.submissionAcknowledged;
    state.wrapped.submissionAcknowledged = true;
    api.submissionAcknowledged = function(...args) {
      const result = originalAck.apply(this, args);
      state.ackPolls += 1;
      if (result && !state.lastAck) emit("submission:acknowledged");
      else if (!result && (state.ackPolls === 1 || state.ackPolls === 5 || state.ackPolls === 12 || state.ackPolls === 20)) {
        emit(`submission:pending:${state.ackPolls}`);
      }
      state.lastAck = !!result;
      return result;
    };
  }

  const pageKey = (() => {
    try { return `${location.origin}${location.pathname}`; } catch (_) { return "unknown"; }
  })();
  if (state.lastPageKey !== pageKey) {
    state.lastPageKey = pageKey;
    state.ackPolls = 0;
    state.lastAck = false;
    emit("page:observed");
    emitLater("page:observed", 1200);
  }
})();
