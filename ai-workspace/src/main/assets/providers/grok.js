window.__AIHUB_CONFIG__ = {
  homeUrl: "https://grok.com/",
  inputSelectors: [
    "textarea",
    "[contenteditable='true']"
  ],
  sendSelectors: [
    "button[aria-label*='Send']",
    "button[type='submit']"
  ],
  responseSelectors: [
    "[data-testid*='message']",
    "article .prose",
    ".markdown"
  ],
  modelSelectors: [
    "button[data-testid*='model' i]",
    "button[aria-label*='model' i]",
    "[role='button'][aria-label*='model' i]"
  ],
  searchSelectors: [
    "button[data-testid*='search' i]",
    "button[aria-label*='search' i]",
    "button[aria-label*='deepsearch' i]"
  ],
  reasoningSelectors: [
    "button[data-testid*='think' i]",
    "button[aria-label*='think' i]",
    "button[aria-label*='reason' i]"
  ],
  deepResearchSelectors: [
    "button[data-testid*='deepsearch' i]",
    "button[aria-label*='deepsearch' i]",
    "button[aria-label*='research' i]"
  ],
  imageGenerationSelectors: [
    "button[data-testid*='image' i]",
    "button[aria-label*='image' i]",
    "button[aria-label*='imagine' i]"
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
    "button[data-testid*='continue' i]",
    "button[aria-label*='continue' i]"
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
    "a[href*='/c/']",
    "a[href*='/chat/']",
    "nav a[href]"
  ],
  voiceSelectors: [
    "button[data-testid*='voice' i]",
    "button[aria-label*='voice' i]",
    "button[aria-label*='microphone' i]"
  ],
  stopSelectors: [
    "button[aria-label*='Stop']"
  ],
  newChatSelectors: [
    "a[href='/']",
    "button[aria-label*='New']"
  ]
};
