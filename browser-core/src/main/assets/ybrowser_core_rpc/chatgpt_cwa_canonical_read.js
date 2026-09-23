"use strict";

/*
 * Canonical ChatGPT conversation read adapter.
 *
 * Architecture and wire-policy are derived from the MIT-licensed
 * kymuco/chatgpt-web-adapter (CWA), especially PR13.0 canonical read v2:
 * - current plural endpoint first
 * - legacy singular endpoint only after current-endpoint HTTP 404
 * - current-endpoint pagination via page_info.start_cursor
 * - bounded 429 backoff
 * - browser-owned authenticated fetch in the existing chatgpt.com page
 *
 * This file is intentionally limited to the browser-owned read plane. It does
 * not submit prompts, export credentials, or treat streaming as finality.
 */
(() => {
  if (globalThis.__YBROWSER_CWA_CANONICAL_READ_V2__) return;

  const SOURCE_EXTENSION = "ybrowser-ai-extension";
  const SOURCE_PAGE = "ybrowser-ai-page";

  const CURRENT_NUM_TURNS = 20;
  const MAX_PAGES = 100;
  const PAGE_PACE_MS = 75;
  const THROTTLE_MAX_RETRIES = 3;
  const THROTTLE_BACKOFF_BASE_MS = 250;
  const THROTTLE_BACKOFF_MAX_MS = 4000;
  const RETRY_AFTER_MAX_MS = 10000;
  const CHUNK_CHARS = 256 * 1024;
  const SESSION_MAX_CHARS = 262144;
  const ACCESS_TOKEN_MAX_CHARS = 100000;

  const sleep = (ms) =>
    new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));

  const safeConversationId = (value) => {
    const id = String(value || "").trim();
    if (!id || /[/?#]/.test(id)) {
      throw new Error("CANONICAL_READ_CONVERSATION_ID_REQUIRED");
    }
    return id;
  };

  const retryAfterMs = (response) => {
    const raw = String(response.headers.get("retry-after") || "").trim();
    if (!raw) return null;

    const seconds = Number(raw);
    if (Number.isFinite(seconds) && seconds >= 0) {
      return Math.min(RETRY_AFTER_MAX_MS, Math.round(seconds * 1000));
    }

    const dateMs = Date.parse(raw);
    if (!Number.isFinite(dateMs)) return null;
    return Math.min(
      RETRY_AFTER_MAX_MS,
      Math.max(0, dateMs - Date.now())
    );
  };

  const responseFailure = (response, contentType) => ({
    ok: false,
    status: Number(response.status || 0),
    contentType,
    reasonCode:
      response.status === 404
        ? "CANONICAL_READ_NOT_VISIBLE"
        : response.status === 401
          ? "CANONICAL_READ_AUTHENTICATION_REQUIRED"
          : response.status === 403
            ? "CANONICAL_READ_ACCESS_CHALLENGED"
            : "CANONICAL_READ_HTTP_ERROR",
    retryable: response.status === 429,
  });

  const loadAccessToken = async (signal) => {
    const response = await fetch("/api/auth/session", {
      method: "GET",
      credentials: "include",
      cache: "no-store",
      headers: { accept: "application/json" },
      signal,
    });

    const contentType = String(
      response.headers.get("content-type") || ""
    ).slice(0, 128);

    if (!response.ok) {
      return {
        ok: false,
        status: response.status,
        contentType,
        reasonCode:
          response.status === 401
            ? "CANONICAL_READ_AUTHENTICATION_REQUIRED"
            : response.status === 403
              ? "CANONICAL_READ_ACCESS_CHALLENGED"
              : "CANONICAL_READ_SESSION_AUTH_FAILED",
      };
    }

    if (!contentType.toLowerCase().includes("json")) {
      return {
        ok: false,
        status: response.status,
        contentType,
        reasonCode: "CANONICAL_READ_SESSION_AUTH_NON_JSON",
      };
    }

    const text = await response.text();
    if (
      !text ||
      text.length > SESSION_MAX_CHARS
    ) {
      return {
        ok: false,
        status: response.status,
        contentType,
        reasonCode: "CANONICAL_READ_SESSION_AUTH_INVALID",
      };
    }

    let payload;
    try {
      payload = JSON.parse(text);
    } catch (_) {
      return {
        ok: false,
        status: response.status,
        contentType,
        reasonCode: "CANONICAL_READ_SESSION_AUTH_INVALID",
      };
    }

    const accessToken =
      typeof payload?.accessToken === "string"
        ? payload.accessToken.trim()
        : "";

    if (
      !accessToken ||
      accessToken.length > ACCESS_TOKEN_MAX_CHARS
    ) {
      return {
        ok: false,
        status: response.status,
        contentType,
        reasonCode: "CANONICAL_READ_SESSION_AUTH_TOKEN_REQUIRED",
      };
    }

    return {
      ok: true,
      accessToken,
    };
  };

  const fetchJson = async ({
    url,
    signal,
    accessToken = "",
    retryThrottle = false,
  }) => {
    for (let attempt = 0; ; attempt += 1) {
      const headers = new Headers({
        accept: "application/json",
      });
      if (accessToken) {
        headers.set("authorization", "Bearer " + accessToken);
      }

      const response = await fetch(url, {
        method: "GET",
        credentials: "include",
        cache: "no-store",
        headers,
        signal,
      });

      const contentType = String(
        response.headers.get("content-type") || ""
      ).slice(0, 128);

      if (
        response.status === 429 &&
        retryThrottle &&
        attempt < THROTTLE_MAX_RETRIES
      ) {
        const hinted = retryAfterMs(response);
        const fallback = Math.min(
          THROTTLE_BACKOFF_MAX_MS,
          THROTTLE_BACKOFF_BASE_MS * (2 ** attempt)
        );
        await sleep(hinted == null ? fallback : hinted);
        continue;
      }

      const text = await response.text();

      if (!response.ok) {
        return {
          ...responseFailure(response, contentType),
          bodyPreview: text.slice(0, 256),
        };
      }

      if (!contentType.toLowerCase().includes("json")) {
        return {
          ok: false,
          status: response.status,
          contentType,
          reasonCode: "CANONICAL_READ_NON_JSON",
        };
      }

      let payload;
      try {
        payload = JSON.parse(text);
      } catch (_) {
        return {
          ok: false,
          status: response.status,
          contentType,
          reasonCode: "CANONICAL_READ_MALFORMED_JSON",
        };
      }

      if (
        !payload ||
        typeof payload !== "object" ||
        Array.isArray(payload)
      ) {
        return {
          ok: false,
          status: response.status,
          contentType,
          reasonCode: "CANONICAL_READ_JSON_OBJECT_REQUIRED",
        };
      }

      return {
        ok: true,
        status: response.status,
        contentType,
        payload,
      };
    }
  };

  const messageIdentity = (item) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      return "";
    }

    const nested =
      item.message &&
      typeof item.message === "object" &&
      !Array.isArray(item.message)
        ? item.message
        : null;

    if (nested) {
      return String(nested.id || item.id || "").trim();
    }

    return String(item.id || "").trim();
  };

  const mergePages = (pagesLatestFirst) => {
    if (!Array.isArray(pagesLatestFirst) || pagesLatestFirst.length === 0) {
      throw new Error("CANONICAL_READ_NO_PAGES");
    }

    const latest = pagesLatestFirst[0];
    const expectedConversationId =
      typeof latest.conversation_id === "string"
        ? latest.conversation_id.trim()
        : "";

    const mergedMessages = [];
    const seen = new Set();

    for (const page of [...pagesLatestFirst].reverse()) {
      if (!page || typeof page !== "object" || Array.isArray(page)) {
        throw new Error("CANONICAL_READ_PAGE_INVALID");
      }

      const pageConversationId =
        typeof page.conversation_id === "string"
          ? page.conversation_id.trim()
          : "";

      if (
        expectedConversationId &&
        pageConversationId &&
        pageConversationId !== expectedConversationId
      ) {
        throw new Error("CANONICAL_READ_PAGINATION_IDENTITY_MISMATCH");
      }

      if (!Array.isArray(page.messages)) {
        throw new Error("CANONICAL_READ_CURRENT_SHAPE_INVALID");
      }

      for (const item of page.messages) {
        const identity = messageIdentity(item);
        if (identity && seen.has(identity)) continue;
        if (identity) seen.add(identity);
        mergedMessages.push(item);
      }
    }

    return {
      ...latest,
      messages: mergedMessages,
      page_info: {
        ...(latest.page_info || {}),
        has_previous_page: false,
        has_next_page: false,
      },
    };
  };

  const normalizePayload = (payload) => {
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new Error("CANONICAL_READ_PAYLOAD_INVALID");
    }

    if (
      payload.mapping &&
      typeof payload.mapping === "object" &&
      !Array.isArray(payload.mapping)
    ) {
      return payload;
    }

    if (!Array.isArray(payload.messages)) {
      throw new Error("CANONICAL_READ_CURRENT_SHAPE_INVALID");
    }

    const records = [];
    const seen = new Set();
    const sourceAliases = new Map();

    for (const item of payload.messages) {
      if (!item || typeof item !== "object" || Array.isArray(item)) {
        continue;
      }

      const nested =
        item.message &&
        typeof item.message === "object" &&
        !Array.isArray(item.message)
          ? item.message
          : null;
      const message = nested || item;

      const messageId = String(message.id || "").trim();
      if (!messageId) {
        throw new Error("CANONICAL_READ_MESSAGE_ID_REQUIRED");
      }
      if (seen.has(messageId)) {
        throw new Error("CANONICAL_READ_DUPLICATE_MESSAGE_ID");
      }
      seen.add(messageId);

      const sourceNodeId = nested
        ? String(item.id || "").trim()
        : "";

      records.push({
        messageId,
        message,
        sourceNodeId,
      });

      if (sourceNodeId) {
        sourceAliases.set(sourceNodeId, messageId);
      }
    }

    const mapping = {};
    records.forEach((record, index) => {
      mapping[record.messageId] = {
        id: record.messageId,
        parent: index > 0 ? records[index - 1].messageId : null,
        children:
          index + 1 < records.length
            ? [records[index + 1].messageId]
            : [],
        message: record.message,
      };
    });

    const sourceCurrentNode = String(
      payload.current_node || payload.current_node_id || ""
    ).trim();

    let currentNode = null;
    if (sourceCurrentNode) {
      const candidate =
        sourceAliases.get(sourceCurrentNode) || sourceCurrentNode;
      if (!Object.prototype.hasOwnProperty.call(mapping, candidate)) {
        throw new Error("CANONICAL_READ_CURRENT_NODE_UNBOUND");
      }
      currentNode = candidate;
    } else if (records.length) {
      currentNode = records[records.length - 1].messageId;
    }

    return {
      ...payload,
      mapping,
      current_node: currentNode,
    };
  };

  const currentUrl = (conversationId, before = null) => {
    const url = new URL(
      "/backend-api/conversations/" +
        encodeURIComponent(conversationId),
      location.origin
    );
    url.searchParams.set("include_has_versions", "true");
    url.searchParams.set("num_turns", String(CURRENT_NUM_TURNS));
    if (before != null) {
      url.searchParams.set("before", before);
    }
    return url.toString();
  };

  const emitCanonicalCapture = ({
    requestId,
    targetWindowId,
    targetPageUrl,
    url,
    statusCode,
    contentType,
    payload,
  }) => {
    const body = JSON.stringify(payload);
    const chunkCount = Math.max(
      1,
      Math.ceil(body.length / CHUNK_CHARS)
    );

    for (let chunkIndex = 0; chunkIndex < chunkCount; chunkIndex += 1) {
      window.postMessage(
        {
          source: SOURCE_PAGE,
          type: "network",
          payload: {
            requestId,
            url,
            method: "GET",
            statusCode,
            contentType,
            body: body.slice(
              chunkIndex * CHUNK_CHARS,
              (chunkIndex + 1) * CHUNK_CHARS
            ),
            chunkIndex,
            chunkCount,
            stream: false,
            complete: true,
            truncated: false,
            capturedAt: Date.now(),
            canonicalRead: true,
            canonicalSource: "cwa-pr13-v2",
            targetWindowId: String(targetWindowId || ""),
            targetPageUrl: String(targetPageUrl || ""),
          },
        },
        location.origin
      );
    }
  };

  const canonicalRead = async ({
    conversationId,
    targetWindowId,
    targetPageUrl,
    includeAllPages,
    timeoutMs,
    requestId,
  }) => {
    if (
      location.hostname !== "chatgpt.com" &&
      !location.hostname.endsWith(".chatgpt.com")
    ) {
      return {
        ok: false,
        reasonCode: "CANONICAL_READ_WRONG_ORIGIN",
      };
    }

    const id = safeConversationId(conversationId);
    const controller = new AbortController();
    const timer = setTimeout(
      () => controller.abort(),
      Math.max(5000, Number(timeoutMs) || 45000)
    );

    try {
      const auth = await loadAccessToken(controller.signal);
      if (auth.ok !== true) return auth;

      const firstUrl = currentUrl(id);
      const first = await fetchJson({
        url: firstUrl,
        signal: controller.signal,
        accessToken: auth.accessToken,
        retryThrottle: true,
      });

      if (first.ok !== true) {
        if (first.status !== 404) return first;

        const legacyUrl =
          location.origin +
          "/backend-api/conversation/" +
          encodeURIComponent(id);
        const legacy = await fetchJson({
          url: legacyUrl,
          signal: controller.signal,
          accessToken: "",
          retryThrottle: false,
        });
        if (legacy.ok !== true) return legacy;

        const normalized = normalizePayload(legacy.payload);
        emitCanonicalCapture({
          requestId,
          targetWindowId,
          targetPageUrl,
          url: legacyUrl,
          statusCode: legacy.status,
          contentType: legacy.contentType,
          payload: normalized,
        });

        return {
          ok: true,
          status: legacy.status,
          endpoint: "legacy",
          pageCount: 1,
          messageCount: Object.keys(normalized.mapping || {}).length,
        };
      }

      let canonicalPayload = first.payload;
      let pageCount = 1;

      if (
        includeAllPages === true &&
        Array.isArray(first.payload.messages)
      ) {
        const pages = [first.payload];
        const seenCursors = new Set();

        while (true) {
          const page = pages[pages.length - 1];
          const pageInfo =
            page &&
            typeof page.page_info === "object" &&
            !Array.isArray(page.page_info)
              ? page.page_info
              : null;

          if (!pageInfo || pageInfo.has_previous_page !== true) break;

          const cursor =
            typeof pageInfo.start_cursor === "string"
              ? pageInfo.start_cursor.trim()
              : "";

          if (!cursor) {
            return {
              ok: false,
              reasonCode: "CANONICAL_READ_PAGINATION_CURSOR_INVALID",
            };
          }
          if (seenCursors.has(cursor)) {
            return {
              ok: false,
              reasonCode: "CANONICAL_READ_PAGINATION_CURSOR_REPEATED",
            };
          }
          if (pages.length >= MAX_PAGES) {
            return {
              ok: false,
              reasonCode: "CANONICAL_READ_PAGINATION_LIMIT",
            };
          }

          seenCursors.add(cursor);
          await sleep(PAGE_PACE_MS);

          const older = await fetchJson({
            url: currentUrl(id, cursor),
            signal: controller.signal,
            accessToken: auth.accessToken,
            retryThrottle: true,
          });

          if (older.ok !== true) return older;
          if (!Array.isArray(older.payload.messages)) {
            return {
              ok: false,
              reasonCode: "CANONICAL_READ_CURRENT_SHAPE_INVALID",
            };
          }

          pages.push(older.payload);
        }

        pageCount = pages.length;
        canonicalPayload = mergePages(pages);
      }

      const normalized = normalizePayload(canonicalPayload);

      emitCanonicalCapture({
        requestId,
        targetWindowId,
        targetPageUrl,
        url: firstUrl,
        statusCode: first.status,
        contentType: first.contentType,
        payload: normalized,
      });

      return {
        ok: true,
        status: first.status,
        endpoint: "current",
        pageCount,
        messageCount: Object.keys(normalized.mapping || {}).length,
      };
    } catch (error) {
      return {
        ok: false,
        reasonCode:
          error &&
          typeof error.message === "string" &&
          /^[A-Z0-9_]+$/.test(error.message)
            ? error.message
            : error?.name === "AbortError"
              ? "CANONICAL_READ_TIMEOUT"
              : "CANONICAL_READ_BROWSER_ERROR",
      };
    } finally {
      clearTimeout(timer);
    }
  };

  window.addEventListener("message", (event) => {
    if (event.source !== window) return;
    const data = event.data;
    if (
      !data ||
      data.source !== SOURCE_EXTENSION ||
      data.type !== "cwa-canonical-read"
    ) {
      return;
    }

    const requestId = String(data.requestId || "");

    Promise.resolve(
      canonicalRead({
        conversationId: data.conversationId,
        targetWindowId: data.targetWindowId,
        targetPageUrl: data.targetPageUrl,
        includeAllPages: data.includeAllPages === true,
        timeoutMs: data.timeoutMs,
        requestId,
      })
    ).then(
      (result) => {
        window.postMessage(
          {
            source: SOURCE_PAGE,
            type: "cwa-canonical-read-result",
            requestId,
            result,
          },
          location.origin
        );
      },
      () => {
        window.postMessage(
          {
            source: SOURCE_PAGE,
            type: "cwa-canonical-read-result",
            requestId,
            result: {
              ok: false,
              reasonCode: "CANONICAL_READ_BROWSER_ERROR",
            },
          },
          location.origin
        );
      }
    );
  });

  globalThis.__YBROWSER_CWA_CANONICAL_READ_V2__ = {
    version: 1,
    upstream: "kymuco/chatgpt-web-adapter PR13.0",
    mode: "browser-owned-canonical-read",
  };
})();
