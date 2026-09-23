package com.yagay.ybrowser.ai.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.provider.ProviderCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class AiBridgeProvider : ContentProvider() {
    private val engine: AiBridgeEngine
        get() = AiBridgeEngine.get(
            requireNotNull(context)
        )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceClient()

        val segments = uri.pathSegments
        if (
            segments.size != 2 ||
            segments[0] !=
                AiBridgeContract.PATH_CONVERSATIONS
        ) {
            return null
        }

        val windowId = segments[1]
        val cursor = MatrixCursor(
            arrayOf(
                "id",
                "role",
                "text",
                "timestamp",
                "attachments",
            )
        )

        engine.messages(windowId).forEach { message ->
            cursor.addRow(
                arrayOf<Any?>(
                    message.id,
                    message.role.name,
                    message.text,
                    message.timestamp,
                    encodeAttachments(
                        message.attachments
                    ),
                )
            )
        }

        cursor.setNotificationUri(
            requireNotNull(context).contentResolver,
            uri,
        )
        return cursor
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.dir/" +
            AiBridgeContract.AUTHORITY +
            ".messages"

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        enforceClient()

        val data = extras ?: Bundle.EMPTY
        val window = resolveWindow(data)
            ?: return Bundle().apply {
                putBoolean(
                    AiBridgeContract.RESULT_OK,
                    false,
                )
                putString(
                    AiBridgeContract.RESULT_FAILURE,
                    "missing-window",
                )
            }

        return when (method) {
            AiBridgeContract.METHOD_ENSURE_SESSION -> {
                engine.updateBinding(window)
                ok()
            }

            AiBridgeContract.METHOD_ATTACH_FILES -> {
                val uris =
                    data.getStringArrayList(
                        AiBridgeContract.EXTRA_URIS
                    ).orEmpty()
                        .map(Uri::parse)

                val result = runBlocking(
                    Dispatchers.IO
                ) {
                    engine.attachFiles(
                        window = window,
                        uris = uris,
                    )
                }

                Bundle().apply {
                    putBoolean(
                        AiBridgeContract.RESULT_OK,
                        result.attachedCount > 0,
                    )
                    putInt(
                        AiBridgeContract.RESULT_ATTACHED_COUNT,
                        result.attachedCount,
                    )
                    putStringArrayList(
                        AiBridgeContract.RESULT_NAMES,
                        ArrayList(result.names),
                    )
                    putString(
                        AiBridgeContract.RESULT_FAILURE,
                        result.failure,
                    )
                }
            }

            AiBridgeContract.METHOD_SEND -> {
                val sent = runBlocking(
                    Dispatchers.IO
                ) {
                    engine.send(
                        window = window,
                        prompt =
                            data.getString(
                                AiBridgeContract.EXTRA_PROMPT
                            ).orEmpty(),
                    )
                }
                Bundle().apply {
                    putBoolean(
                        AiBridgeContract.RESULT_OK,
                        sent,
                    )
                    engine.currentUrl(window)
                        ?.let {
                            putString(
                                AiBridgeContract.RESULT_URL,
                                it,
                            )
                        }
                }
            }

            AiBridgeContract.METHOD_STOP -> {
                runBlocking(Dispatchers.IO) {
                    engine.stop(window)
                }
                ok()
            }

            AiBridgeContract.METHOD_RESPONSE_SNAPSHOT -> {
                val snapshot = runBlocking(
                    Dispatchers.IO
                ) {
                    engine.responseSnapshot(window)
                }
                Bundle().apply {
                    putBoolean(
                        AiBridgeContract.RESULT_OK,
                        true,
                    )
                    putString(
                        AiBridgeContract.RESULT_TEXT,
                        snapshot.text,
                    )
                    putString(
                        AiBridgeContract.RESULT_KEY,
                        snapshot.key,
                    )
                    putString(
                        AiBridgeContract.RESULT_SOURCE,
                        snapshot.source,
                    )
                    putInt(
                        AiBridgeContract.RESULT_RESPONSE_COUNT,
                        snapshot.responseCount,
                    )
                    putInt(
                        AiBridgeContract.RESULT_TURN_COUNT,
                        snapshot.turnCount,
                    )
                    putString(
                        AiBridgeContract.RESULT_STATE,
                        snapshot.state,
                    )
                    putString(
                        AiBridgeContract.RESULT_REASON,
                        snapshot.reason,
                    )
                    putString(
                        AiBridgeContract.RESULT_PATH,
                        snapshot.path,
                    )
                    putLong(
                        AiBridgeContract.RESULT_QUIET_MS,
                        snapshot.quietMs,
                    )
                }
            }

            AiBridgeContract.METHOD_CURRENT_URL -> {
                Bundle().apply {
                    putBoolean(
                        AiBridgeContract.RESULT_OK,
                        true,
                    )
                    putString(
                        AiBridgeContract.RESULT_URL,
                        engine.currentUrl(window),
                    )
                }
            }

            AiBridgeContract.METHOD_RELOAD -> {
                engine.reload(window)
                ok()
            }

            AiBridgeContract
                .METHOD_MARK_ATTACHMENTS_SUBMITTED -> {
                engine.markAttachmentsSubmitted(
                    window
                )
                ok()
            }

            else ->
                super.call(method, arg, extras)
                    ?: Bundle.EMPTY
                    ?: Bundle.EMPTY
        }
    }

    private fun resolveWindow(
        data: Bundle,
    ): ChatWindow? {
        val windowId =
            data.getString(
                AiBridgeContract.EXTRA_WINDOW_ID
            )?.takeIf { it.isNotBlank() }
                ?: return null

        val stored =
            engine.storedSession(windowId)

        val providerId =
            data.getString(
                AiBridgeContract.EXTRA_PROVIDER_ID
            )?.takeIf { it.isNotBlank() }
                ?: stored?.providerId
                ?: "chatgpt"

        val provider =
            ProviderCatalog.byId(providerId)

        return ChatWindow(
            id = windowId,
            providerId = provider.id,
            title =
                data.getString(
                    AiBridgeContract.EXTRA_TITLE
                )?.takeIf { it.isNotBlank() }
                    ?: stored?.title
                    ?: provider.name,
            url =
                data.getString(
                    AiBridgeContract.EXTRA_URL
                )?.takeIf { it.isNotBlank() }
                    ?: stored?.url,
            boundUrl =
                data.getString(
                    AiBridgeContract.EXTRA_BOUND_URL
                )?.takeIf { it.isNotBlank() }
                    ?: stored?.boundUrl,
            boundRepo =
                data.getString(
                    AiBridgeContract.EXTRA_BOUND_REPO
                )?.takeIf { it.isNotBlank() }
                    ?: stored?.boundRepo,
            boundProject =
                data.getString(
                    AiBridgeContract.EXTRA_BOUND_PROJECT
                )?.takeIf { it.isNotBlank() }
                    ?: stored?.boundProject,
        )
    }

    private fun enforceClient() {
        val caller = callingPackage
        if (
            caller != null &&
            caller !=
                AiBridgeContract.AIHUB_PACKAGE &&
            caller !=
                AiBridgeContract.YBROWSER_PACKAGE
        ) {
            throw SecurityException(
                "Unsupported AI bridge client: $caller"
            )
        }
    }

    private fun ok() =
        Bundle().apply {
            putBoolean(
                AiBridgeContract.RESULT_OK,
                true,
            )
        }

    private fun encodeAttachments(
        attachments: List<AttachmentMeta>,
    ): String =
        JSONArray().apply {
            attachments.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("name", item.name)
                        .put(
                            "mimeType",
                            item.mimeType,
                        )
                        .put(
                            "sizeBytes",
                            item.sizeBytes,
                        )
                )
            }
        }.toString()

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
