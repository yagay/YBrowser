package com.yagay.ybrowser.ai

object AiWorkspaceContract {
    const val ACTION_OPEN_AI =
        "com.yagay.YBrowser.action.OPEN_AI"
    const val ACTION_OPEN_AI_SESSION =
        "com.yagay.YBrowser.action.OPEN_AI_SESSION"
    const val ACTION_OPEN_AI_WEB =
        "com.yagay.YBrowser.action.OPEN_AI_WEB"

    const val ACTION_LEGACY_AIHUB_OPEN_AI =
        "com.yagay.AIHub.action.OPEN_AI"
    const val ACTION_LEGACY_AIHUB_OPEN_AI_WEB =
        "com.yagay.AIHub.action.OPEN_AI_WEB"
    const val ACTION_LEGACY_AIHUB_BINDING_SYNC =
        "com.yagay.AIHub.action.CHATGPT_BINDING_SYNC"

    // Reuse the existing YBrowser/YagaYHub wire names so callers can upgrade
    // without migrating their stored bindings.
    const val EXTRA_URL =
        "com.yagay.YBrowser.extra.URL"
    const val EXTRA_TARGETS_JSON =
        "com.yagay.YBrowser.extra.CHAT_TARGETS_JSON"
    const val EXTRA_BIND_REPO =
        "com.yagay.YBrowser.extra.BIND_REPO"
    const val EXTRA_BIND_PROJECT =
        "com.yagay.YBrowser.extra.BIND_PROJECT"
    const val EXTRA_BIND_TITLE =
        "com.yagay.YBrowser.extra.BIND_TITLE"
    const val EXTRA_BIND_URL =
        "com.yagay.YBrowser.extra.BIND_URL"

    const val YAGAYHUB_PACKAGE =
        "com.yagay.YagaYHub"
    const val ACTION_REQUEST_BINDING =
        "com.yagay.YagaYHub.action.REQUEST_CHATGPT_BINDING"
    const val ACTION_NOTIFY_BINDING_REMOVE =
        "com.yagay.YagaYHub.action.REMOVE_CHATGPT_BINDING"
    const val ACTION_LOCAL_BINDING_REMOVE =
        "com.yagay.YBrowser.action.CHATGPT_BINDING_REMOVE"

    const val EXTRA_PROVIDER_ID =
        "com.yagay.YBrowser.extra.AI_PROVIDER_ID"
    const val EXTRA_WINDOW_ID =
        "com.yagay.YBrowser.extra.AI_WINDOW_ID"
    const val EXTRA_REQUESTER_PACKAGE =
        "com.yagay.YBrowser.extra.BIND_REQUESTER_PACKAGE"

    const val ACTIVITY_CLASS =
        "com.yagay.ybrowser.ai.AiWorkspaceActivity"
}
