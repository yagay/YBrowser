(() => {
  const api = window.__AIHUB__;
  const cfg = window.__AIHUB_CONFIG__ || {};
  if (!api) return;

  const simpleHash = (value) => {
    let hash = 2166136261;
    const source = String(value || "");
    for (let i = 0; i < source.length; i++) {
      hash ^= source.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16);
  };

  const visible = (node) => {
    if (!node) return false;
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

  const usable = (node) => !!node && visible(node) && !node.disabled && node.getAttribute?.("aria-disabled") !== "true";
  const text = (node) => (node?.innerText || node?.textContent || node?.getAttribute?.("aria-label") || node?.getAttribute?.("title") || "").replace(/\s+/g, " ").trim();
  const candidates = () => Array.from(document.querySelectorAll("button, [role='button'], [role='menuitem'], [role='option'], [role='radio'], a, input[type='button']")).filter(usable);

  const defs = {
    model: {
      selectors: () => cfg.modelSelectors || [
        "button[data-testid*='model' i]", "[data-testid*='model-switcher' i]", "button[aria-label*='model' i]",
        "[role='button'][aria-label*='model' i]", "button[aria-label*='模式选择器' i]", "button[aria-label*='当前模式' i]"
      ],
      rx: /(^|\b)(model|models|模型|模式选择器|当前模式)(\b|$)/i
    },
    search: {
      selectors: () => cfg.searchSelectors || [
        "button[data-testid*='web-search' i]", "button[aria-label*='search the web' i]",
        "button[aria-label*='web search' i]", "[role='button'][aria-label*='web search' i]"
      ],
      rx: /(search( the)? web|web search|browse the web|browse web|联网搜索|搜索网页|网页搜索)/i,
      exclude: /(search chats?|chat search|search history|conversation search|搜索聊天|搜索对话|聊天记录搜索)/i
    },
    reasoning: { selectors: () => cfg.reasoningSelectors || ["button[data-testid*='reason' i]", "button[aria-label*='reason' i]", "button[data-testid*='think' i]", "button[aria-label*='think' i]"], rx: /(reasoning|reason|think|thinking|deepthink|深度思考|思考|推理)/i },
    deepResearch: { selectors: () => cfg.deepResearchSelectors || [], rx: /(deep research|research mode|深度研究|深入研究)/i },
    imageGeneration: { selectors: () => cfg.imageGenerationSelectors || [], rx: /(create image|generate image|image generation|绘图|生成图片|图像生成)/i },
    tools: { selectors: () => cfg.toolsSelectors || ["button[data-testid*='tool' i]", "button[aria-label*='tool' i]", "[role='button'][aria-label*='tool' i]"], rx: /(^|\b)(tools?|工具|上传和工具)(\b|$)/i },
    retry: { selectors: () => cfg.retrySelectors || ["button[data-testid*='regenerate' i]", "button[data-testid*='retry' i]", "button[aria-label*='regenerate' i]", "button[aria-label*='retry' i]"], rx: /(regenerate|retry|try again|redo|重新生成|重试)/i },
    continue: { selectors: () => cfg.continueSelectors || ["button[data-testid*='continue' i]", "button[aria-label*='continue' i]"], rx: /(continue generating|continue response|continue|继续生成|继续回答)/i },
    copy: { selectors: () => cfg.copyButtonSelectors || ["button[data-testid*='copy' i]", "button[aria-label*='copy' i]"], rx: /(^|\b)(copy|复制)(\b|$)/i },
    edit: { selectors: () => cfg.editSelectors || ["button[data-testid*='edit' i]", "button[aria-label*='edit' i]"], rx: /(^|\b)(edit|编辑)(\b|$)/i },
    conversationMenu: { selectors: () => cfg.conversationMenuSelectors || ["button[data-testid*='conversation' i][aria-haspopup='menu']", "button[aria-label*='conversation' i][aria-haspopup='menu']"], rx: /(conversation options|chat options|对话选项|会话选项)/i },
    rename: { selectors: () => cfg.renameSelectors || [], rx: /(^|\b)(rename|重命名)(\b|$)/i },
    deleteConversation: { selectors: () => cfg.deleteSelectors || [], rx: /(delete chat|delete conversation|删除对话|删除会话)/i },
    history: { selectors: () => cfg.historySelectors || ["nav a[href*='/c/']", "nav a[href*='/chat/']", "aside a[href]"], rx: /(history|chat history|search chats?|历史记录|聊天记录|搜索聊天)/i },
    voice: { selectors: () => cfg.voiceSelectors || ["button[data-testid*='voice' i]", "button[aria-label*='voice' i]", "button[aria-label*='microphone' i]", "button[aria-label*='mic' i]"], rx: /(voice|microphone|mic|语音|麦克风)/i }
  };

  const findBySelectors = (selectors) => all(selectors).find(usable) || null;
  const findByText = (def) => candidates().find((node) => {
    const value = text(node);
    if (def.exclude && def.exclude.test(value)) return false;
    return def.rx.test(value);
  }) || null;
  const findAction = (name) => {
    const def = defs[name];
    if (!def) return null;
    const direct = findBySelectors(def.selectors());
    if (direct) {
      const value = text(direct);
      if (!def.exclude || !def.exclude.test(value)) return direct;
    }
    return findByText(def);
  };
  const clickAction = (name) => {
    const node = findAction(name);
    if (!node) return "not-found";
    node.click();
    return "ok";
  };

  const menuItems = () => Array.from(document.querySelectorAll("[role='menuitem'], [role='option'], [role='radio'], [role='listbox'] [role='option'], mat-option, [data-value]")).filter(visible);
  const dedupeLabels = (nodes) => {
    const seen = new Set();
    const out = [];
    nodes.forEach((node) => {
      const value = text(node);
      if (!value || value.length > 120) return;
      const key = value.toLowerCase();
      if (!seen.has(key)) {
        seen.add(key);
        out.push(value);
      }
    });
    return out.slice(0, 80);
  };
  const currentModel = () => {
    const node = findAction("model");
    const value = text(node);
    return value.length <= 120 ? value : "";
  };
  const findError = () => {
    const nodes = Array.from(document.querySelectorAll("[role='alert'], [aria-live='assertive'], [class*='error' i]"));
    for (const node of nodes) {
      if (!visible(node)) continue;
      const value = text(node);
      if (value && value.length <= 500) return value;
    }
    return "";
  };
  const bool = (value) => !!value;

  api.capabilities = () => {
    const attachmentInput = document.querySelector("input[type='file']");
    return {
      text: bool((cfg.inputSelectors || []).length),
      attachments: bool(attachmentInput || window.AIHubNativeFiles),
      stop: bool((cfg.stopSelectors || []).length),
      newChat: bool((cfg.newChatSelectors || []).length || cfg.homeUrl),
      model: bool(findAction("model")),
      search: bool(findAction("search")),
      reasoning: bool(findAction("reasoning")),
      deepResearch: bool(findAction("deepResearch")),
      imageGeneration: bool(findAction("imageGeneration")),
      tools: bool(findAction("tools")),
      retry: bool(findAction("retry")),
      continue: bool(findAction("continue")),
      copy: bool(findAction("copy")),
      edit: bool(findAction("edit")),
      conversationMenu: bool(findAction("conversationMenu")),
      rename: bool(findAction("rename")),
      deleteConversation: bool(findAction("deleteConversation")),
      history: bool(findAction("history") || document.querySelectorAll("a[href*='/c/'], a[href*='/chat/'], a[href*='/app/']").length),
      voice: bool(findAction("voice")),
      currentModel: currentModel(),
      title: document.title || "",
      pathHash: simpleHash(location.pathname),
      error: findError()
    };
  };

  api.optionList = (kind) => {
    if (kind !== "model" && kind !== "tool" && kind !== "mode") return [];
    return dedupeLabels(menuItems());
  };
  api.openOptionPicker = (kind) => {
    if (kind === "model") return clickAction("model");
    if (kind === "tool") return clickAction("tools");
    if (kind === "mode") {
      const reason = findAction("reasoning");
      if (reason) { reason.click(); return "ok"; }
      return "not-found";
    }
    return "unsupported";
  };
  api.selectOption = (kind, wanted) => {
    const target = String(wanted || "").trim().toLowerCase();
    if (!target) return "empty-value";
    const choose = () => {
      const items = menuItems();
      const exact = items.find((node) => text(node).toLowerCase() === target);
      const partial = items.find((node) => text(node).toLowerCase().includes(target));
      const node = exact || partial;
      if (!node) return false;
      node.click();
      return true;
    };
    if (choose()) return "ok";
    const opened = api.openOptionPicker(kind);
    if (opened !== "ok") return opened;
    [120, 280, 520].forEach((delay) => setTimeout(() => choose(), delay));
    return "scheduled";
  };
  api.performAction = (name, value) => {
    const action = String(name || "");
    if (action === "newChat") return api.newChat();
    if (action === "stop") return api.stop();
    if (action === "model" || action === "tools" || action === "reasoning" || action === "search" || action === "deepResearch" || action === "imageGeneration" || action === "retry" || action === "continue" || action === "copy" || action === "edit" || action === "conversationMenu" || action === "history" || action === "voice") {
      return clickAction(action);
    }
    if (action === "rename" || action === "deleteConversation") {
      let result = clickAction(action);
      if (result === "ok") return result;
      const menu = findAction("conversationMenu");
      if (!menu) return "not-found";
      menu.click();
      [100, 220, 420].forEach((delay) => setTimeout(() => clickAction(action), delay));
      return "scheduled";
    }
    if (action === "selectModel") return api.selectOption("model", value);
    if (action === "selectTool") return api.selectOption("tool", value);
    if (action === "selectMode") return api.selectOption("mode", value);
    return "unsupported";
  };
})();
