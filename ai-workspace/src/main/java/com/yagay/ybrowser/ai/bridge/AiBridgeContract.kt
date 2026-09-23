package com.yagay.ybrowser.ai.bridge

object AiBridgeContract {
    const val AUTHORITY = "com.yagay.YBrowser.ai.bridge"
    const val BASE_URI = "content://$AUTHORITY"

    const val AIHUB_PACKAGE = "com.yagay.aihub"
    const val YBROWSER_PACKAGE = "com.yagay.YBrowser"

    const val METHOD_ENSURE_SESSION = "ensure_session"
    const val METHOD_ATTACH_FILES = "attach_files"
    const val METHOD_SEND = "send"
    const val METHOD_STOP = "stop"
    const val METHOD_RESPONSE_SNAPSHOT = "response_snapshot"
    const val METHOD_CURRENT_URL = "current_url"
    const val METHOD_RELOAD = "reload"
    const val METHOD_MARK_ATTACHMENTS_SUBMITTED =
        "mark_attachments_submitted"

    const val EXTRA_WINDOW_ID = "window_id"
    const val EXTRA_PROVIDER_ID = "provider_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_URL = "url"
    const val EXTRA_BOUND_URL = "bound_url"
    const val EXTRA_BOUND_REPO = "bound_repo"
    const val EXTRA_BOUND_PROJECT = "bound_project"
    const val EXTRA_PROMPT = "prompt"
    const val EXTRA_URIS = "uris"

    const val RESULT_OK = "ok"
    const val RESULT_ATTACHED_COUNT = "attached_count"
    const val RESULT_NAMES = "names"
    const val RESULT_FAILURE = "failure"
    const val RESULT_URL = "url"

    const val RESULT_TEXT = "text"
    const val RESULT_KEY = "key"
    const val RESULT_SOURCE = "source"
    const val RESULT_RESPONSE_COUNT = "response_count"
    const val RESULT_TURN_COUNT = "turn_count"
    const val RESULT_STATE = "state"
    const val RESULT_REASON = "reason"
    const val RESULT_PATH = "path"
    const val RESULT_QUIET_MS = "quiet_ms"

    const val PATH_CONVERSATIONS = "conversations"
}
