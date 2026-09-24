"use strict";

let privacyPolicy = {
  doNotTrackEnabled: true,
  globalPrivacyControlEnabled: true,
  webRtcProtectionMode: "STANDARD",
};

function replaceHeader(headers, name, value) {
  const lower = name.toLowerCase();
  const filtered = (headers || []).filter(
    (header) => String(header.name || "").toLowerCase() !== lower
  );
  if (value != null) {
    filtered.push({ name, value });
  }
  return filtered;
}

function applyRequestHeaders(details) {
  let headers = details.requestHeaders || [];
  headers = replaceHeader(
    headers,
    "DNT",
    privacyPolicy.doNotTrackEnabled ? "1" : null,
  );
  headers = replaceHeader(
    headers,
    "Sec-GPC",
    privacyPolicy.globalPrivacyControlEnabled ? "1" : null,
  );
  return { requestHeaders: headers };
}

try {
  browser.webRequest.onBeforeSendHeaders.addListener(
    applyRequestHeaders,
    { urls: ["http://*/*", "https://*/*"] },
    ["blocking", "requestHeaders"],
  );
} catch (_) {}

async function setPrivacySetting(setting, value) {
  if (!setting) return;
  try {
    const level = await setting.get({});
    if (
      level &&
      level.levelOfControl &&
      !["controllable_by_this_extension", "controlled_by_this_extension"]
        .includes(level.levelOfControl)
    ) {
      return;
    }
    await setting.set({ value });
  } catch (_) {}
}

async function clearPrivacySetting(setting) {
  if (!setting) return;
  try {
    await setting.clear({});
  } catch (_) {}
}

async function applyWebRtcPolicy(mode) {
  const network = browser.privacy && browser.privacy.network;
  if (!network) return;

  const peerConnectionEnabled = network.peerConnectionEnabled;
  const ipHandlingPolicy = network.webRTCIPHandlingPolicy;

  switch (String(mode || "STANDARD")) {
    case "BLOCK":
      await setPrivacySetting(peerConnectionEnabled, false);
      await clearPrivacySetting(ipHandlingPolicy);
      break;

    case "HIDE_LOCAL_IP":
      await clearPrivacySetting(peerConnectionEnabled);
      await setPrivacySetting(
        ipHandlingPolicy,
        "default_public_interface_only",
      );
      break;

    case "DISABLE_NON_PROXIED_UDP":
      await clearPrivacySetting(peerConnectionEnabled);
      await setPrivacySetting(
        ipHandlingPolicy,
        "disable_non_proxied_udp",
      );
      break;

    case "PROTECT_IP":
      await clearPrivacySetting(peerConnectionEnabled);
      await setPrivacySetting(
        ipHandlingPolicy,
        "proxy_only",
      );
      break;

    case "STANDARD":
    default:
      await clearPrivacySetting(peerConnectionEnabled);
      await clearPrivacySetting(ipHandlingPolicy);
      break;
  }
}

browser.runtime.onMessage.addListener((message) => {
  if (!message || message.type !== "privacy-policy") return;
  privacyPolicy = {
    doNotTrackEnabled: message.doNotTrackEnabled === true,
    globalPrivacyControlEnabled:
      message.globalPrivacyControlEnabled === true,
    webRtcProtectionMode:
      String(message.webRtcProtectionMode || "STANDARD"),
  };
  applyWebRtcPolicy(privacyPolicy.webRtcProtectionMode);
});
