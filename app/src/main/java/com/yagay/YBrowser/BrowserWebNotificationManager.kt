package com.yagay.YBrowser

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate

object GeckoWebNotificationBridge {
    private const val CHANNEL_ID = "ybrowser_web_notifications"
    internal const val EXTRA_NOTIFICATION = "web_notification"
    internal const val EXTRA_SOURCE = "web_notification_source"
    internal const val EXTRA_ID = "web_notification_id"
    internal const val ACTION_CLICK = "com.yagay.YBrowser.web_notification.CLICK"
    internal const val ACTION_DISMISS = "com.yagay.YBrowser.web_notification.DISMISS"

    @Volatile
    private var installedRuntime: GeckoRuntime? = null

    fun ensureInstalled(
        context: Context,
        runtime: GeckoRuntime,
    ) {
        if (installedRuntime === runtime) return
        synchronized(this) {
            if (installedRuntime === runtime) return
            val appContext = context.applicationContext
            createChannel(appContext)
            runtime.setWebNotificationDelegate(
                object : WebNotificationDelegate {
                    override fun onShowNotification(
                        notification: WebNotification,
                    ) {
                        show(appContext, notification)
                    }

                    override fun onCloseNotification(
                        notification: WebNotification,
                    ) {
                        close(appContext, notification)
                    }
                }
            )
            installedRuntime = runtime
        }
    }

    private fun show(
        context: Context,
        web: WebNotification,
    ) {
        if (web.privateBrowsing || !canPost(context)) {
            runOnMain { web.dismiss() }
            return
        }

        val id = notificationId(web)
        val clickIntent = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, BrowserWebNotificationReceiver::class.java).apply {
                action = ACTION_CLICK
                putExtra(EXTRA_NOTIFICATION, web)
                putExtra(EXTRA_SOURCE, web.source ?: web.origin)
                putExtra(EXTRA_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissIntent = PendingIntent.getBroadcast(
            context,
            id xor 0x5A5A,
            Intent(context, BrowserWebNotificationReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_NOTIFICATION, web)
                putExtra(EXTRA_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(web.title?.takeIf { it.isNotBlank() } ?: web.origin)
            .setContentText(web.text.orEmpty())
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(web.text.orEmpty())
            )
            .setContentIntent(clickIntent)
            .setDeleteIntent(dismissIntent)
            .setAutoCancel(!web.requireInteraction)
            .setOngoing(web.requireInteraction)
            .setSilent(web.silent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)

        if (!web.silent && web.vibrate.isNotEmpty()) {
            builder.setVibrate(web.vibrate.map(Int::toLong).toLongArray())
        }

        context.getSystemService(NotificationManager::class.java)
            .notify(id, builder.build())
        runOnMain { web.show() }
    }

    private fun close(
        context: Context,
        web: WebNotification,
    ) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(web))
        runOnMain { web.dismiss() }
    }

    private fun notificationId(web: WebNotification): Int {
        val stable = (web.origin + "|" + web.tag).hashCode()
        return if (stable == Int.MIN_VALUE) 1 else kotlin.math.abs(stable)
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "网站通知",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "由已授权网站发送的通知"
            }
        )
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}

class BrowserWebNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val web = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                GeckoWebNotificationBridge.EXTRA_NOTIFICATION,
                WebNotification::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<WebNotification>(
                GeckoWebNotificationBridge.EXTRA_NOTIFICATION,
            )
        }
        val id = intent.getIntExtra(
            GeckoWebNotificationBridge.EXTRA_ID,
            0,
        )
        val manager = context.getSystemService(NotificationManager::class.java)

        when (intent.action) {
            GeckoWebNotificationBridge.ACTION_CLICK -> {
                web?.let {
                    runCatching { it.click() }
                    runCatching { it.dismiss() }
                }
                if (id != 0) manager.cancel(id)
                val source = intent.getStringExtra(
                    GeckoWebNotificationBridge.EXTRA_SOURCE,
                ).orEmpty()
                if (
                    source.startsWith("http://") ||
                    source.startsWith("https://")
                ) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_OPEN_URL
                            putExtra(MainActivity.EXTRA_URL, source)
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            )
                        }
                    )
                }
            }

            GeckoWebNotificationBridge.ACTION_DISMISS -> {
                web?.let { runCatching { it.dismiss() } }
                if (id != 0) manager.cancel(id)
            }
        }
    }
}
