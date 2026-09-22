window.__AIHUB_CONFIG__ = {
  homeUrl: "https://gemini.google.com/app",
  inputSelectors: [
    "rich-textarea div[contenteditable='true']",
    "div.ql-editor[contenteditable='true']",
    "div[contenteditable='true'][aria-label*='Enter a prompt' i]",
    "[contenteditable='true'][role='textbox']",
    "div[contenteditable='true']",
    "textarea"
  ],
  loggedInSelectors: [
    "input-area-v2",
    "rich-textarea",
    "button[aria-label*='New chat' i]",
    "button[aria-label*='发起新对话' i]",
    "a[href='/app']"
  ],
  attachmentButtonSelectors: [
    "button[aria-label='Upload & tools']",
    "button[aria-label='上传和工具']",
    "button[aria-label='Open upload file menu']",
    "button[aria-label*='Upload & tools' i]",
    "button[aria-label*='upload file menu' i]",
    "button[aria-label*='Upload' i]"
  ],
  uploadMenuItemSelectors: [
    "images-files-uploader[data-test-id='uploader-images-files-button-advanced']",
    "[data-test-id='local-images-files-uploader-icon']",
    "[data-test-id='uploader-images-files-button-advanced']",
    "images-files-uploader",
    "[role='menuitem'] [data-test-id*='uploader' i]"
  ],
  sendSelectors: [
    "button[aria-label='Send message']",
    "button[aria-label='发送消息']",
    "button[aria-label*='Send' i]",
    "button[mattooltip*='Send' i]",
    ".send-button",
    "button.send-button",
    "gem-icon-button.submit[aria-disabled='false']"
  ],
  turnSelectors: [
    "model-response"
  ],
  assistantTurnSelectors: [
    "model-response"
  ],
  responseSelectors: [
    "model-response message-content",
    "model-response .model-response-text",
    "model-response .message-content",
    "model-response .response-content",
    "message-content.model-response-text",
    "model-response"
  ],
  responseContentSelectors: [
    "message-content.model-response-text",
    "message-content",
    ".model-response-text",
    ".response-content",
    "structured-content-container.model-response-text",
    "structured-content-container"
  ],
  assistantMarkerSelectors: [
    "model-response",
    "message-content.model-response-text",
    "structured-content-container.model-response-text"
  ],
  modelSelectors: [
    "button[aria-label*='model' i]",
    "button[aria-label*='mode selector' i]",
    "button[aria-label*='current mode' i]",
    "button[aria-label*='模式选择器' i]",
    "button[aria-label*='当前模式' i]",
    "[data-test-id='model-selector']",
    "[role='button'][aria-label*='model' i]",
    "[role='button'][aria-label*='模式' i]"
  ],
  toolsSelectors: [
    "button[aria-label='Upload & tools']",
    "button[aria-label='上传和工具']",
    "button[aria-label*='tools' i]",
    "button[aria-label*='tool' i]"
  ],
  searchSelectors: [
    "button[aria-label*='search the web' i]",
    "button[aria-label*='web search' i]",
    "button[aria-label*='联网' i]",
    "button[aria-label*='网页搜索' i]",
    "[data-test-id*='search' i]"
  ],
  reasoningSelectors: [
    "button[aria-label*='thinking' i]",
    "button[aria-label*='reasoning' i]",
    "button[aria-label*='思考' i]",
    "button[aria-label*='推理' i]",
    "[data-test-id*='thinking' i]"
  ],
  deepResearchSelectors: [
    "button[aria-label*='deep research' i]",
    "button[aria-label*='深度研究' i]",
    "[data-test-id*='deep-research' i]",
    "[data-test-id*='research' i]"
  ],
  imageGenerationSelectors: [
    "button[aria-label*='image' i]",
    "button[aria-label*='图像' i]",
    "button[aria-label*='图片' i]",
    "[data-test-id*='image-generation' i]"
  ],
  retrySelectors: [
    "button[aria-label='Redo']",
    "button[aria-label*='redo' i]",
    "button[aria-label*='regenerate' i]",
    "button[aria-label*='重新生成' i]",
    "button[data-test-id*='redo' i]"
  ],
  copyButtonSelectors: [
    "button[aria-label='Copy']",
    "button[aria-label='复制']",
    "button[aria-label*='copy' i]"
  ],
  editSelectors: [
    "button[aria-label*='edit' i]",
    "button[aria-label*='编辑' i]",
    "button[data-test-id*='edit' i]"
  ],
  historySelectors: [
    "a[href*='/app/']",
    "nav a[href*='/app/']"
  ],
  voiceSelectors: [
    "button[aria-label*='microphone' i]",
    "button[aria-label*='voice' i]",
    "button[aria-label*='语音' i]",
    "button[aria-label*='麦克风' i]",
    "[data-test-id*='mic' i]"
  ],
  stopSelectors: [
    "button[aria-label*='Stop response' i]",
    "button[aria-label*='Stop' i]",
    "button[aria-label*='停止' i]",
    "[aria-busy='true']"
  ],
  newChatSelectors: [
    "button[aria-label*='New chat' i]",
    "button[aria-label*='发起新对话' i]",
    "expandable-button[aria-label*='New chat' i] button",
    "a[href='/app']"
  ]
};
