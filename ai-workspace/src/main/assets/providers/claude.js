window.__AIHUB_CONFIG__ = {
  homeUrl: "https://claude.ai/new",
  inputSelectors: [
    "div.ProseMirror[contenteditable='true']",
    "[contenteditable='true'][data-placeholder]",
    "fieldset [contenteditable='true']"
  ],
  sendSelectors: [
    "button[aria-label='Send message']",
    "button[aria-label*='Send' i]"
  ],
  responseSelectors: [
    "[data-is-streaming] .font-claude-message",
    ".font-claude-message",
    "[data-testid*='assistant']"
  ],
  modelSelectors: [
    "button[data-testid*='model' i]",
    "[data-testid*='model-selector' i]",
    "button[aria-label*='model' i]",
    "[role='button'][aria-label*='model' i]"
  ],
  reasoningSelectors: [
    "button[data-testid*='thinking' i]",
    "button[aria-label*='thinking' i]",
    "button[aria-label*='extended' i]"
  ],
  searchSelectors: [
    "button[data-testid*='search' i]",
    "button[aria-label*='search' i]"
  ],
  deepResearchSelectors: [
    "button[data-testid*='research' i]",
    "button[aria-label*='research' i]"
  ],
  toolsSelectors: [
    "button[data-testid*='tool' i]",
    "button[aria-label*='tool' i]"
  ],
  retrySelectors: [
    "button[data-testid*='retry' i]",
    "button[data-testid*='regenerate' i]",
    "button[aria-label*='retry' i]",
    "button[aria-label*='regenerate' i]"
  ],
  continueSelectors: [
    "button[aria-label*='continue' i]",
    "button[data-testid*='continue' i]"
  ],
  copyButtonSelectors: [
    "button[data-testid*='copy' i]",
    "button[aria-label*='copy' i]"
  ],
  editSelectors: [
    "button[data-testid*='edit' i]",
    "button[aria-label*='edit' i]"
  ],
  historySelectors: [
    "a[href*='/chat/']",
    "nav a[href*='/chat/']"
  ],
  conversationMenuSelectors: [
    "button[aria-label*='conversation' i][aria-haspopup='menu']",
    "button[aria-label*='chat options' i]"
  ],
  voiceSelectors: [
    "button[data-testid*='voice' i]",
    "button[aria-label*='voice' i]",
    "button[aria-label*='microphone' i]"
  ],
  stopSelectors: [
    "button[aria-label*='Stop']",
    "button[data-testid*='stop']"
  ],
  newChatSelectors: [
    "a[href='/new']",
    "button[aria-label*='New chat']"
  ]
};
