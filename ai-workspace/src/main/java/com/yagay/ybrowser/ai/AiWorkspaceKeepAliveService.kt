package com.yagay.ybrowser.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger

/**
 * Keeps the YBrowser/Gecko process at foreground-service importance while the
 * AI browser workspace is not visible. Live GeckoSessions remain in memory;
 * returning to the workspace can therefore reattach the existing page instead
 * of restoring SessionState and reloading the network document.
 */
class AiWorkspaceKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AI 浏览器后台保活",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持 AI 浏览器标签页和会话在后台存活"
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val count = intent
            ?.getIntExtra(EXTRA_SESSION_COUNT, 1)
            ?.coerceAtLeast(1)
            ?: 1

        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("AI 标签页保持运行")
                .setContentText("后台保持 $count 个 AI 浏览器会话")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build(),
        )
        DiagnosticLogger.i(
            "WORKSPACE_LIFECYCLE",
            "keepalive_started sessions=$count",
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DiagnosticLogger.i(
            "WORKSPACE_LIFECYCLE",
            "keepalive_stopped",
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "ai_workspace_keepalive"
        private const val NOTIFICATION_ID = 4217
        private const val EXTRA_SESSION_COUNT = "session_count"

        fun start(context: Context, sessionCount: Int) {
            if (sessionCount <= 0) return
            val intent = Intent(
                context,
                AiWorkspaceKeepAliveService::class.java,
            ).putExtra(EXTRA_SESSION_COUNT, sessionCount)
            runCatching {
                context.startForegroundService(intent)
            }.onFailure {
                DiagnosticLogger.e(
                    "WORKSPACE_LIFECYCLE",
                    "keepalive_start_failed",
                    it,
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(
                    Intent(
                        context,
                        AiWorkspaceKeepAliveService::class.java,
                    ),
                )
            }
        }
    }
}
