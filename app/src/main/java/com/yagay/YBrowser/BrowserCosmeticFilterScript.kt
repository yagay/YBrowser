package com.yagay.YBrowser

import org.json.JSONArray

/** Identical page-domain/exception semantics for WebView and Gecko's bridge. */
internal object BrowserCosmeticFilterScript {
    fun build(rules: List<String>): String {
        val payload = JSONArray().apply {
            rules.take(3500).forEach(::put)
        }.toString()
        return TEMPLATE.replace("__RULES__", payload)
    }

    private val TEMPLATE = """
(function () {
  "use strict";
  const rules = __RULES__;
  const host = location.hostname.toLowerCase().replace(/\.$/, "");
  const matches = (domains) => !domains || domains.split(",").some((entry) => {
    const domain = entry.trim().toLowerCase();
    return domain.length > 0 && (host === domain || host.endsWith("." + domain));
  });
  const exceptions = new Set();
  for (const raw of rules) {
    if (typeof raw !== "string") continue;
    const i = raw.indexOf("#@#");
    if (i >= 0 && matches(raw.slice(0, i))) {
      exceptions.add(raw.slice(i + 3));
    }
  }
  const selectors = [];
  for (const raw of rules) {
    if (typeof raw !== "string") continue;
    const i = raw.indexOf("##");
    if (i < 0 || !matches(raw.slice(0, i))) continue;
    const selector = raw.slice(i + 2);
    if (!selector || exceptions.has(selector)) continue;
    try {
      document.createDocumentFragment().querySelector(selector);
      selectors.push(selector);
    } catch (_) {}
  }
  const apply = () => {
    if (!document.documentElement) return;
    let style = document.getElementById("ybrowser-cosmetic-style");
    if (!style) {
      style = document.createElement("style");
      style.id = "ybrowser-cosmetic-style";
      document.documentElement.appendChild(style);
    }
    style.textContent = selectors.map((s) =>
      s + " { display: none !important; visibility: hidden !important; }"
    ).join("\n");
  };
  if (document.documentElement) apply();
  else document.addEventListener("DOMContentLoaded", apply, { once: true });
})();
""".trimIndent()
}
