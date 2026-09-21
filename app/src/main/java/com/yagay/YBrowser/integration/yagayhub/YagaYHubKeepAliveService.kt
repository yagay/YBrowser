package com.yagay.YBrowser.integration.yagayhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.yagay.YBrowser.BrowserSessionRegistry
import com.yagay.YBrowser.R

class YagaYHubKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val count = intent?.getIntExtra(EXTRA_SESSION_COUNT, 1)
            ?.coerceAtLeast(1)
            ?: 1
        startForeground(
            NOTIFICATION_ID,
            buildNotification(count),
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AI 会话保活",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持 YagaYHub 绑定的 AI 网页会话活跃"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(count: Int): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_keepalive)
            .setContentTitle("AI 会话保持运行")
            .setContentText("正在保持 " + count + " 个绑定页面的连接")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val CHANNEL_ID = "ai_session_keepalive"
        private const val NOTIFICATION_ID = 4216
        private const val EXTRA_SESSION_COUNT = "session_count"

        fun start(
            context: Context,
            sessionCount: Int,
        ) {
            val intent = Intent(
                context,
                YagaYHubKeepAliveService::class.java,
            ).putExtra(
                EXTRA_SESSION_COUNT,
                sessionCount.coerceAtLeast(1),
            )
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(
                    Intent(
                        context,
                        YagaYHubKeepAliveService::class.java,
                    ),
                )
            }
        }

        fun syncWithSessionPool(context: Context) {
            val count = BrowserSessionRegistry.activeCount(
                YagaYHubContract.RETAINED_SESSION_POOL_KEY,
            )
            if (count > 0) start(context, count)
            else stop(context)
        }
    }
}
