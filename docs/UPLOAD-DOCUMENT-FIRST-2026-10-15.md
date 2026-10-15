# File input compatibility fix (2026-10-15)

HTML file uploading uses Android's Storage Access Framework document picker (OpenDocument and OpenMultipleDocuments) for regular files on both System WebView and GeckoView. This covers images chosen through the **Files** UI as well as PDF, ZIP, TXT and other documents. GET_CONTENT from WebView's FileChooserParams can launch a photo-only picker on Android, so its Intent is now fallback-only when DocumentsUI fails to launch.

File upload results are tied to the engine's original ValueCallback / Gecko FilePrompt, not a path/query string. SPA navigations change URLs without changing the document. Tab closes cancel the pending host prompt; duplicate completions remain bounded by BrowserFileChooserDelivery.

Capture and folder upload are deliberately separate. WebView fallback results also extract data and ClipData from vendor pickers if parseResult returns null. GeckoView keeps asynchronous SAF-to-file staging for its file-URI requirements.

Device verification: HTML input accept=*/* uploads PDF/ZIP, accept=image/* opens Files and uploads the image, multiple selection, SPA URL changes while picker open, tab closed during picker, WebView popups, Gecko normal/private tabs.
