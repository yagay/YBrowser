package com.yagay.YBrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Process-priority support for existing live tabs. Does not fake activity or reload pages. */
class BrowserTabsKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "浏览器标签后台运行", NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持已打开的普通标签和在线对话网页会话"
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 0)?.coerceAtLeast(0) ?: 0
        if (count == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ai_keepalive)
                .setContentTitle("YBrowser 标签保持活跃")
                .setContentText("后台保留 $count 个普通网页会话")
                .setContentIntent(launch)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build(),
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "ybrowser_live_tabs"
        private const val NOTIFICATION_ID = 4218
        private const val EXTRA_COUNT = "count"

        fun update(context: Context, count: Int) {
            if (count <= 0) {
                stop(context)
                return
            }
            val intent = Intent(context, BrowserTabsKeepAliveService::class.java)
                .putExtra(EXTRA_COUNT, count)
            runCatching { context.startForegroundService(intent) }
                .onFailure {
                    BrowserNavigationLog.log(
                        context, "TAB_KEEPALIVE",
                        "foreground service denied: " + it.javaClass.simpleName +
                            " " + it.message.orEmpty(),
                    )
                }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, BrowserTabsKeepAliveService::class.java))
            }
        }
    }
}
