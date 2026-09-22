(() => {
  const api = window.__AIHUB__;
  const attachmentState = window.__AIHUB_ATTACHMENT_STATE__ || {};
  if (!api || typeof api.send !== "function") return;

  const originalSend = api.send.bind(api);
  api.send = (text) => {
    const chars = String(text || "").trim().length;
    const attachedAt = Number(attachmentState.lastAttachedAt || 0);
    const attachedCount = Number(attachmentState.lastAttachedCount || 0);
    const recentAttachment = attachedCount > 0 && attachedAt > 0 && Date.now() - attachedAt < 45000;
    const result = originalSend(text);

    if (chars === 0 && recentAttachment && result === "verify") {
      const sendState = window.__AIHUB_SEND_STATE__ || (window.__AIHUB_SEND_STATE__ = {});
      sendState.queuedAttachmentOnly = true;
      sendState.queueAcceptedAt = Date.now();
      return "queued";
    }
    return result;
  };
})();
