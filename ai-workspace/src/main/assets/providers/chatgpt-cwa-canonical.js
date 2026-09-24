/*
 * CWA-aligned canonical conversation read for YBrowser Gecko.
 *
 * Architecture and read semantics are adapted from:
 * https://github.com/kymuco/chatgpt-web-adapter
 * pinned main: 1d449bc22614c5bc27f1e1cb4bfaeed3794d5921
 * public release: v0.3.0
 * license: MIT
 *
 * This file intentionally contains only the browser-owned canonical READ plane.
 * It does not implement protected writes, challenge solving, token synthesis,
 * automatic retries of user messages, or fallback write transports.
 */
(() => {
  const root =
    window.__YBROWSER_CWA__ ||
    (window.__YBROWSER_CWA__ = {});

  if (typeof root.canonicalRead === "function") return;

  const ORIGIN = "https://chatgpt.com";
  const NUM_TURNS = 20;
  const MAX_PAGES = 100;
  const MAX_BODY_CHARS = 48 * 1024 * 1024;
  const SESSION_MAX_CHARS = 262_144;
  const ACCESS_TOKEN_MAX_CHARS = 100_000;
  const THROTTLE_RETRIES = 3;
  const BACKOFF_BASE_MS = 250;
  const BACKOFF_MAX_MS = 4000;
  const RETRY_AFTER_MAX_MS = 10_000;
  const PAGE_PACE_MS = 75;

  const sleep = (ms) =>
    new Promise((resolve) => setTimeout(resolve, ms));

  const messageIdentity = (item) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      return null;
    }
    if (
      item.message &&
      typeof item.message === "object" &&
      !Array.isArray(item.message)
    ) {
      return (
        (typeof item.message.id === "string" && item.message.id) ||
        (typeof item.id === "string" && item.id) ||
        null
      );
    }
    return typeof item.id === "string" && item.id
      ? item.id
      : null;
  };

  const reasonForStatus = (status) => {
    if (status === 401) {
      return "CANONICAL_READ_AUTHENTICATION_REQUIRED";
    }
    if (status === 403) {
      return "CANONICAL_READ_ACCESS_CHALLENGED";
    }
    if (status === 404) {
      return "CANONICAL_READ_NOT_VISIBLE";
    }
    if (status === 429) {
      return "CANONICAL_READ_THROTTLED";
    }
    return "CANONICAL_READ_HTTP_ERROR";
  };

  const retryAfterMs = (response) => {
    const raw = (response.headers.get("retry-after") || "").trim();
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

  const fetchJson = async (
    url,
    headers,
    signal,
    retryThrottle = false
  ) => {
    for (let attempt = 0; ; attempt += 1) {
      const response = await fetch(url, {
        method: "GET",
        credentials: "include",
        cache: "no-store",
        headers,
        signal,
      });
      const contentType = (
        response.headers.get("content-type") || ""
      ).slice(0, 128);

      if (response.status === 429 && retryThrottle) {
        if (attempt >= THROTTLE_RETRIES) {
          return {
            ok: false,
            status: response.status,
            contentType,
            reason: "CANONICAL_READ_THROTTLE_EXHAUSTED",
            retryable: true,
          };
        }
        const hintedDelay = retryAfterMs(response);
        const fallbackDelay = Math.min(
          BACKOFF_MAX_MS,
          BACKOFF_BASE_MS * (2 ** attempt)
        );
        await sleep(
          hintedDelay === null ? fallbackDelay : hintedDelay
        );
        continue;
      }

      if (!response.ok) {
        return {
          ok: false,
          status: response.status,
          contentType,
          reason: reasonForStatus(response.status),
          retryable: response.status === 404,
        };
      }

      if (!contentType.toLowerCase().includes("json")) {
        return {
          ok: false,
          status: response.status,
          contentType,
          reason: "CANONICAL_READ_NON_JSON",
          retryable: false,
        };
      }

      const text = await response.text();
      if (!text || text.length > MAX_BODY_CHARS) {
        return {
          ok: false,
          status: response.status,
          contentType,
          reason: "CANONICAL_READ_BODY_INVALID",
          retryable: false,
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
          reason: "CANONICAL_READ_MALFORMED_JSON",
          retryable: false,
        };
      }

      if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        return {
          ok: false,
          status: response.status,
          contentType,
          reason: "CANONICAL_READ_JSON_OBJECT_REQUIRED",
          retryable: false,
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

  root.canonicalRead = async (
    conversationId,
    includeAllPages = false,
    timeoutMs = 30_000
  ) => {
    const id =
      typeof conversationId === "string"
        ? conversationId.trim()
        : "";
    if (
      !id ||
      id.includes("/") ||
      id.includes("?") ||
      id.includes("#")
    ) {
      return {
        ok: false,
        status: 0,
        reason: "CANONICAL_READ_CONVERSATION_ID_REQUIRED",
      };
    }

    const encoded = encodeURIComponent(id);
    const currentBase =
      ORIGIN + "/backend-api/conversations/" + encoded;
    const legacyEndpoint =
      ORIGIN + "/backend-api/conversation/" + encoded;
    const safeTimeoutMs = Math.max(
      1_000,
      Math.min(
        Number.isFinite(timeoutMs) ? Number(timeoutMs) : 30_000,
        120_000
      )
    );
    const controller = new AbortController();
    const timer = setTimeout(
      () => controller.abort(),
      safeTimeoutMs
    );

    try {
      let accessToken = "";
      try {
        const sessionResponse = await fetch(
          ORIGIN + "/api/auth/session",
          {
            method: "GET",
            credentials: "include",
            cache: "no-store",
            headers: {
              accept: "application/json",
            },
            signal: controller.signal,
          }
        );
        if (sessionResponse.ok) {
          const sessionText = await sessionResponse.text();
          if (
            sessionText &&
            sessionText.length <= SESSION_MAX_CHARS
          ) {
            const sessionPayload = JSON.parse(sessionText);
            const candidate =
              typeof sessionPayload?.accessToken === "string"
                ? sessionPayload.accessToken.trim()
                : "";
            if (
              candidate &&
              candidate.length <= ACCESS_TOKEN_MAX_CHARS
            ) {
              accessToken = candidate;
            }
          }
        }
      } catch (_) {}

      const currentHeaders = new Headers({
        accept: "application/json",
      });
      if (accessToken) {
        currentHeaders.set(
          "authorization",
          "Bearer " + accessToken
        );
      }

      const currentUrl = (before = null) => {
        const url = new URL(currentBase);
        url.searchParams.set(
          "include_has_versions",
          "true"
        );
        url.searchParams.set(
          "num_turns",
          String(NUM_TURNS)
        );
        if (before !== null) {
          url.searchParams.set("before", before);
        }
        return url.toString();
      };

      const first = await fetchJson(
        currentUrl(),
        currentHeaders,
        controller.signal,
        true
      );

      if (!first.ok) {
        if (first.status !== 404) {
          return first;
        }

        const legacy = await fetchJson(
          legacyEndpoint,
          new Headers({
            accept: "application/json",
          }),
          controller.signal,
          false
        );
        if (!legacy.ok) return legacy;

        if (
          !legacy.payload.mapping ||
          typeof legacy.payload.mapping !== "object"
        ) {
          return {
            ok: false,
            status: legacy.status,
            contentType: legacy.contentType,
            reason: "CANONICAL_READ_LEGACY_SHAPE_INVALID",
          };
        }

        return {
          ok: true,
          status: legacy.status,
          contentType: legacy.contentType,
          endpoint: legacyEndpoint,
          body: JSON.stringify(legacy.payload),
        };
      }

      if (
        !Array.isArray(first.payload.messages)
      ) {
        if (
          first.payload.mapping &&
          typeof first.payload.mapping === "object"
        ) {
          return {
            ok: true,
            status: first.status,
            contentType: first.contentType,
            endpoint: currentUrl(),
            body: JSON.stringify(first.payload),
          };
        }
        return {
          ok: false,
          status: first.status,
          contentType: first.contentType,
          reason: "CANONICAL_READ_CURRENT_SHAPE_INVALID",
        };
      }

      if (includeAllPages !== true) {
        return {
          ok: true,
          status: first.status,
          contentType: first.contentType,
          endpoint: currentUrl(),
          body: JSON.stringify(first.payload),
        };
      }

      const pages = [first.payload];
      const seenCursors = new Set();

      while (true) {
        const page = pages[pages.length - 1];
        const pageInfo = page.page_info;
        if (
          !pageInfo ||
          pageInfo.has_previous_page !== true
        ) {
          break;
        }

        const cursor =
          typeof pageInfo.start_cursor === "string"
            ? pageInfo.start_cursor.trim()
            : "";
        if (!cursor || seenCursors.has(cursor)) {
          return {
            ok: false,
            status: first.status,
            contentType: first.contentType,
            reason: cursor
              ? "CANONICAL_READ_PAGINATION_CURSOR_REPEATED"
              : "CANONICAL_READ_PAGINATION_CURSOR_INVALID",
          };
        }
        if (pages.length >= MAX_PAGES) {
          return {
            ok: false,
            status: first.status,
            contentType: first.contentType,
            reason: "CANONICAL_READ_PAGINATION_LIMIT",
          };
        }

        seenCursors.add(cursor);
        if (PAGE_PACE_MS > 0) {
          await sleep(PAGE_PACE_MS);
        }
        const older = await fetchJson(
          currentUrl(cursor),
          currentHeaders,
          controller.signal,
          true
        );
        if (!older.ok) return older;
        if (!Array.isArray(older.payload.messages)) {
          return {
            ok: false,
            status: older.status,
            contentType: older.contentType,
            reason: "CANONICAL_READ_CURRENT_SHAPE_INVALID",
          };
        }

        if (
          typeof first.payload.conversation_id === "string" &&
          typeof older.payload.conversation_id === "string" &&
          first.payload.conversation_id !==
            older.payload.conversation_id
        ) {
          return {
            ok: false,
            status: older.status,
            contentType: older.contentType,
            reason:
              "CANONICAL_READ_PAGINATION_IDENTITY_MISMATCH",
          };
        }

        pages.push(older.payload);
      }

      const mergedMessages = [];
      const seenMessageIds = new Set();
      for (const page of pages.slice().reverse()) {
        for (const item of page.messages) {
          const identity = messageIdentity(item);
          if (
            identity &&
            seenMessageIds.has(identity)
          ) {
            continue;
          }
          if (identity) {
            seenMessageIds.add(identity);
          }
          mergedMessages.push(item);
        }
      }

      const mergedPayload = {
        ...first.payload,
        messages: mergedMessages,
        page_info: {
          ...(first.payload.page_info || {}),
          has_previous_page: false,
          has_next_page: false,
        },
      };
      const body = JSON.stringify(mergedPayload);
      if (body.length > MAX_BODY_CHARS) {
        return {
          ok: false,
          status: first.status,
          contentType: first.contentType,
          reason: "CANONICAL_READ_BODY_INVALID",
        };
      }

      return {
        ok: true,
        status: first.status,
        contentType: first.contentType,
        endpoint: currentUrl(),
        body,
      };
    } catch (error) {
      const timedOut =
        error?.name === "AbortError" ||
        controller.signal.aborted;
      return {
        ok: false,
        status: 0,
        reason: timedOut
          ? "CANONICAL_READ_TIMEOUT"
          : "CANONICAL_READ_BROWSER_ERROR",
        retryable: timedOut,
      };
    } finally {
      clearTimeout(timer);
    }
  };
})();
