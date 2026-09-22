window.__AIHUB_CONFIG__ = {
  homeUrl: "https://chatgpt.com/",
  inputSelectors: [
    "#mobile-composer-prompt",
    "textarea[name='prompt'][aria-label*='Chat with ChatGPT' i]",
    "textarea[placeholder*='Ask ChatGPT' i]",
    "#prompt-textarea[contenteditable='true']",
    "#prompt-textarea",
    "[data-testid='prompt-textarea']",
    "textarea[name='prompt-textarea']",
    "textarea[data-id='prompt-textarea']",
    "div[contenteditable='true'][role='textbox'][aria-label*='Chat' i]",
    "div[contenteditable='true'].ProseMirror",
    "textarea[aria-label*='Chat' i]",
    "textarea[placeholder*='Ask' i]",
    "textarea[data-id='root']",
    "[contenteditable='true'][data-placeholder]"
  ],
  loggedInSelectors: [
    "#mobile-composer-prompt",
    "#prompt-textarea",
    "button[aria-label='Add files and more']",
    "[data-testid='composer-plus-btn']",
    "a[data-testid='create-new-chat-button']",
    "#history a[href^='/c/']",
    "#history a[href^='/uc/']",
    "a[href^='/c/']",
    "a[href^='/uc/']",
    "button[data-testid='model-switcher-dropdown-button']"
  ],
  attachmentButtonSelectors: [
    "button[aria-label='Add files and more']",
    "button[aria-label*='Add files' i]",
    "[data-testid='composer-plus-btn']"
  ],
  sendSelectors: [
    "button[type='submit'][aria-label='Send message']",
    "button[aria-label='Send message']",
    "#composer-submit-button",
    "button[data-testid='send-button']",
    "button[data-testid*='composer-send']",
    "button[aria-label='Send prompt']",
    "button[aria-label='发送提示']",
    "button[aria-label*='Send dictated message' i]",
    "button.composer-submit-btn",
    "form button[type='submit']"
  ],
  responseSelectors: [
    "article[data-testid^='conversation-turn'][data-message-author-role='assistant']",
    "article[data-testid^='conversation-turn'][data-turn='assistant']",
    "article[data-testid^='conversation-turn'] [data-message-author-role='assistant']",
    "article[data-testid^='conversation-turn'] [data-turn='assistant']",
    "div[data-testid^='conversation-turn'] [data-message-author-role='assistant']",
    "div[data-testid^='conversation-turn'] [data-turn='assistant']",
    "section[data-testid^='conversation-turn'] [data-message-author-role='assistant']",
    "section[data-testid^='conversation-turn'] [data-turn='assistant']",
    "[data-message-author-role='assistant'] .markdown",
    "[data-turn='assistant'] .markdown",
    "[data-message-author-role='assistant']",
    "[data-turn='assistant']",
    ".agent-turn .markdown",
    ".agent-turn"
  ],
  responseContentSelectors: [
    "[data-message-author-role='assistant'] .markdown",
    "[data-turn='assistant'] .markdown",
    ".markdown",
    ".prose",
    "[class*='markdown']",
    "[data-message-author-role='assistant']",
    "[data-turn='assistant']"
  ],
  turnSelectors: [
    "article[data-testid^='conversation-turn']",
    "div[data-testid^='conversation-turn']",
    "section[data-testid^='conversation-turn']",
    "article[data-message-author-role]",
    "div[data-message-author-role]",
    "section[data-message-author-role]",
    "article[data-turn]",
    "div[data-turn]",
    "section[data-turn]"
  ],
  assistantMarkerSelectors: [
    "[data-message-author-role='assistant']",
    "[data-turn='assistant']"
  ],
  modelSelectors: [
    "button[data-testid='model-switcher-dropdown-button']",
    "button[aria-label*='model' i]",
    "button[aria-haspopup='menu'][data-tone='neutral']",
    "button.__composer-pill"
  ],
  searchSelectors: [
    "button[aria-label*='search the web' i]",
    "button[aria-label*='web search' i]",
    "button[data-testid*='web-search' i]"
  ],
  deepResearchSelectors: [
    "button[aria-label*='deep research' i]",
    "button[data-testid*='deep-research' i]"
  ],
  reasoningSelectors: [
    "button[data-testid*='intelligence' i]",
    "button[data-testid*='reasoning' i]",
    "button[aria-label*='reasoning' i]",
    "button[aria-label*='thinking' i]"
  ],
  toolsSelectors: [
    "button[aria-label='Add files and more']",
    "button[data-testid*='tools' i]",
    "button[aria-label*='tools' i]"
  ],
  retrySelectors: [
    "button[data-testid*='regenerate' i]",
    "button[data-testid*='retry' i]",
    "button[aria-label*='regenerate' i]",
    "button[aria-label*='retry' i]"
  ],
  continueSelectors: [
    "button[data-testid*='continue' i]",
    "button[aria-label*='continue' i]"
  ],
  copyButtonSelectors: [
    "button[aria-label='Copy response']",
    "button[data-testid='copy-turn-action-button']",
    "button[aria-label='Copy']",
    "button[aria-label*='Copy' i]"
  ],
  editSelectors: [
    "button[data-testid*='edit' i]",
    "button[aria-label*='edit' i]"
  ],
  conversationMenuSelectors: [
    "button[data-testid*='conversation' i][aria-haspopup='menu']",
    "button[aria-label*='chat options' i]",
    "button[aria-label*='conversation options' i]"
  ],
  historySelectors: [
    "#history a[href^='/c/']",
    "#history a[href^='/uc/']",
    "nav a[href^='/c/']",
    "nav a[href^='/uc/']"
  ],
  voiceSelectors: [
    "button[data-testid*='voice' i]",
    "button[data-testid*='speech' i]",
    "button[aria-label*='voice' i]",
    "button[aria-label*='microphone' i]"
  ],
  stopSelectors: [
    "button[data-testid='stop-button']",
    "button[aria-label*='Stop' i]"
  ],
  newChatSelectors: [
    "a[data-testid='create-new-chat-button']",
    "a[aria-label='New chat']",
    "button[aria-label*='New chat' i]",
    "a[href='/']"
  ]
};
