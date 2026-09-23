package com.yagay.ybrowser.ai.data

import android.content.Context
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.WindowSessionKey

class PendingAttachmentStore(
    context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            "aihub_pending_attachments",
            Context.MODE_PRIVATE,
        )

    fun save(
        session: WindowSessionKey,
        attachments: List<AttachmentMeta>,
    ) {
        prefs.edit()
            .putString(
                session.storageKey,
                PendingAttachmentCodec
                    .encode(attachments),
            )
            .apply()
    }

    fun load(
        session: WindowSessionKey,
    ): List<AttachmentMeta> {
        val raw =
            prefs.getString(
                session.storageKey,
                "",
            ).orEmpty()
        val decoded =
            PendingAttachmentCodec.decode(raw)

        if (
            decoded.repaired ||
            decoded.sourceSchema !=
                PendingAttachmentCodec
                    .CURRENT_SCHEMA
        ) {
            prefs.edit()
                .putString(
                    session.storageKey,
                    PendingAttachmentCodec
                        .encode(
                            decoded.attachments
                        ),
                )
                .apply()

            DiagnosticLogger.w(
                "ATTACHMENT_STORE",
                "pending_attachment_state_migrated provider=" +
                    session.providerId +
                    " window=" +
                    session.windowId.take(12) +
                    " sourceSchema=" +
                    decoded.sourceSchema +
                    " repaired=" +
                    decoded.repaired,
            )
        }

        return decoded.attachments
    }

    fun consume(
        session: WindowSessionKey,
    ): List<AttachmentMeta> {
        val result = load(session)
        clear(session)
        return result
    }

    fun clear(
        session: WindowSessionKey,
    ) {
        prefs.edit()
            .remove(session.storageKey)
            .apply()
    }
}
