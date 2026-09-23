package com.yagay.ybrowser.ai.data

import android.content.Context
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.ChatWindow

class WindowStore(context: Context) {
    private val prefs =
        context.getSharedPreferences(
            "aihub_workspace",
            Context.MODE_PRIVATE,
        )

    fun load(): List<ChatWindow> {
        val raw =
            prefs.getString(
                KEY_WINDOWS,
                "",
            ).orEmpty()
        val decoded =
            WorkspaceStateCodec.decode(raw)

        if (
            decoded.repaired ||
            decoded.sourceSchema !=
                WorkspaceStateCodec.CURRENT_SCHEMA
        ) {
            if (
                raw.isNotBlank() &&
                decoded.sourceSchema == 0
            ) {
                prefs.edit()
                    .putString(
                        KEY_CORRUPT_BACKUP,
                        raw.take(
                            MAX_BACKUP_CHARS
                        ),
                    )
                    .apply()
            }

            prefs.edit()
                .putString(
                    KEY_WINDOWS,
                    WorkspaceStateCodec
                        .encode(
                            decoded.windows
                        ),
                )
                .putInt(
                    KEY_SCHEMA,
                    WorkspaceStateCodec
                        .CURRENT_SCHEMA,
                )
                .apply()

            DiagnosticLogger.w(
                "WORKSPACE_STORE",
                "workspace_state_migrated sourceSchema=" +
                    decoded.sourceSchema +
                    " repaired=" +
                    decoded.repaired +
                    " windows=" +
                    decoded.windows.size,
            )
        }

        return decoded.windows
    }

    fun save(
        windows: List<ChatWindow>,
    ) {
        prefs.edit()
            .putString(
                KEY_WINDOWS,
                WorkspaceStateCodec
                    .encode(windows),
            )
            .putInt(
                KEY_SCHEMA,
                WorkspaceStateCodec
                    .CURRENT_SCHEMA,
            )
            .apply()
    }

    fun loadActiveId(): String? =
        prefs.getString(
            KEY_ACTIVE,
            null,
        )

    fun saveActiveId(id: String) {
        prefs.edit()
            .putString(
                KEY_ACTIVE,
                id,
            )
            .apply()
    }

    companion object {
        private const val KEY_WINDOWS =
            "windows"
        private const val KEY_ACTIVE =
            "active_window"
        private const val KEY_SCHEMA =
            "schema_version"
        private const val KEY_CORRUPT_BACKUP =
            "windows_corrupt_backup"
        private const val MAX_BACKUP_CHARS =
            256 * 1024
    }
}
