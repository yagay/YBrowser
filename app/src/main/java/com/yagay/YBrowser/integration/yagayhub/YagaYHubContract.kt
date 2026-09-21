package com.yagay.YBrowser.integration.yagayhub

object YagaYHubContract {
    const val PACKAGE = "com.yagay.YagaYHub"

    // Legacy action names are intentionally retained for cross-app compatibility.
    const val ACTION_SELECT_BINDING =
        "com.yagay.YBrowser.action.SELECT_CHATGPT_CHAT"
    const val ACTION_SELECT_BINDING_POPUP =
        "com.yagay.YBrowser.action.SELECT_CHATGPT_CHAT_POPUP"
    const val ACTION_BINDING_RESULT =
        "com.yagay.YagaYHub.action.CHATGPT_BOUND"
    const val ACTION_BINDING_SYNC =
        "com.yagay.YBrowser.action.CHATGPT_BINDING_SYNC"
    const val ACTION_BINDING_REMOVE =
        "com.yagay.YBrowser.action.CHATGPT_BINDING_REMOVE"
    const val ACTION_REQUEST_BINDING =
        "com.yagay.YagaYHub.action.REQUEST_CHATGPT_BINDING"
    const val ACTION_NOTIFY_BINDING_REMOVE =
        "com.yagay.YagaYHub.action.REMOVE_CHATGPT_BINDING"

    const val EXTRA_URL = "com.yagay.YBrowser.extra.URL"
    const val EXTRA_REUSE_EXISTING =
        "com.yagay.YBrowser.extra.REUSE_EXISTING"
    const val EXTRA_BINDING_MODE =
        "com.yagay.YBrowser.extra.YAGAYHUB_BINDING_MODE"
    const val EXTRA_COMPACT_MODE =
        "com.yagay.YBrowser.extra.YAGAYHUB_COMPACT_MODE"
    const val EXTRA_BIND_REPO =
        "com.yagay.YBrowser.extra.BIND_REPO"
    const val EXTRA_BIND_PROJECT =
        "com.yagay.YBrowser.extra.BIND_PROJECT"
    const val EXTRA_BIND_URL =
        "com.yagay.YBrowser.extra.BIND_URL"
    const val EXTRA_BIND_TITLE =
        "com.yagay.YBrowser.extra.BIND_TITLE"
    const val EXTRA_TARGETS_JSON =
        "com.yagay.YBrowser.extra.CHAT_TARGETS_JSON"

    const val RETAINED_SESSION_POOL_KEY =
        "yagayhub_bound_ai_sessions"
}
