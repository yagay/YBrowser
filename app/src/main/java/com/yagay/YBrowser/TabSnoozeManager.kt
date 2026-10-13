package com.yagay.YBrowser

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

data class SnoozedTab(
    val id: Long,
    val url: String,
    val title: String,
    val wakeAt: Long,
)

object TabSnoozeManager {
    private const val PREFS = "ybrowser_snoozed_tabs"
    private const val KEY_ITEMS = "items"
    const val CHANNEL_ID = "ybrowser_snoozed_tabs"

    fun list(context: Context): List<SnoozedTab> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optLong("id", 0L)
                val url = obj.optString("url")
                val wakeAt = obj.optLong("wakeAt", 0L)
                if (id <= 0L || url.isBlank() || wakeAt <= 0L) continue
                add(
                    SnoozedTab(
                        id = id,
                        url = url,
                        title = obj.optString("title").ifBlank { url },
                        wakeAt = wakeAt,
                    ),
                )
            }
        }.sortedBy { it.wakeAt }
    }

    @Synchronized
    fun snooze(
        context: Context,
        tab: BrowserTab,
        wakeAt: Long,
    ): Boolean {
        if (tab.privateMode || tab.url.isBlank() ||
            wakeAt <= System.currentTimeMillis()) return false
        val previous = list(context)
        val replaced = previous.filter { it.url == tab.url }
        val retained = previous.filterNot { it.url == tab.url }
        // Never silently drop an earlier alarm to make room for a later tab.
        if (retained.size >= SnoozedTabPolicy.MAX_ITEMS) return false
        val id = SnoozedTabPolicy.nextId(previous.map { it.id }, System.currentTimeMillis())
        val item = SnoozedTab(
            id = id,
            url = tab.url,
            title = tab.title.ifBlank { tab.url },
            wakeAt = wakeAt,
        )
        if (runCatching { schedule(context, item) }.isFailure) return false
        save(context, (retained + item).sortedBy { it.wakeAt })
        replaced.forEach { cancel(context, it.id) }
        return true
    }

    @Synchronized
    fun remove(context: Context, id: Long) {
        cancel(context, id)
        save(context, list(context).filterNot { it.id == id })
    }

    @Synchronized
    fun consumeDue(context: Context, id: Long, now: Long): SnoozedTab? {
        val items = list(context)
        val due = items.firstOrNull { it.id == id && it.wakeAt <= now } ?: return null
        save(context, items.filterNot { it.id == id })
        return due
    }

    fun reschedulePending(context: Context, item: SnoozedTab) {
        runCatching { schedule(context, item) }
    }

    fun rescheduleAfterBoot(context: Context) {
        val now = System.currentTimeMillis()
        list(context).forEach { item ->
            if (item.wakeAt <= now) {
                if (notifyDue(context, item)) consumeDue(context, item.id, now)
            } else {
                runCatching { schedule(context, item) }
            }
        }
    }

    @Synchronized
    fun popDue(context: Context, now: Long = System.currentTimeMillis()): List<SnoozedTab> {
        val items = list(context)
        val due = items.filter { it.wakeAt <= now }
        if (due.isNotEmpty()) {
            save(context, items.filter { it.wakeAt > now })
        }
        return due
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "休眠标签",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "在休眠标签到期时提醒"
            },
        )
    }

    fun notifyDue(context: Context, item: SnoozedTab): Boolean {
        createNotificationChannel(context)
        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_URL
            putExtra(MainActivity.EXTRA_URL, item.url)
            putExtra(MainActivity.EXTRA_REUSE_EXISTING, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            requestCode(item.id),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_keepalive)
            .setContentTitle("休眠标签已到时间")
            .setContentText(item.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.title + "\n" + item.url))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context)
                .notify(requestCode(item.id), notification)
            true
        }.getOrDefault(false)
    }

    private fun schedule(context: Context, item: SnoozedTab) {
        val intent = Intent(context, SnoozedTabReceiver::class.java).apply {
            putExtra("id", item.id)
            putExtra("url", item.url)
            putExtra("title", item.title)
            putExtra("wakeAt", item.wakeAt)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(item.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                item.wakeAt,
                pending,
            )
    }

    private fun cancel(context: Context, id: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(id),
            Intent(context, SnoozedTabReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    private fun save(context: Context, items: List<SnoozedTab>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("url", item.url)
                    .put("title", item.title)
                    .put("wakeAt", item.wakeAt),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    private fun requestCode(id: Long): Int =
        (id xor (id ushr 32)).toInt() and 0x7fffffff
}

class SnoozedTabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getLongExtra("id", 0L) ?: return
        if (id <= 0L) return
        // Never trust stale PendingIntent extras: the snooze may have been
        // removed or replaced by another alarm for the same URL.
        val now = System.currentTimeMillis()
        val stored = TabSnoozeManager.list(context).firstOrNull { it.id == id }
            ?: return
        if (stored.wakeAt > now) {
            // A clock adjustment may wake an RTC alarm early. Restore it.
            TabSnoozeManager.reschedulePending(context, stored)
            return
        }
        if (TabSnoozeManager.notifyDue(context, stored)) {
            TabSnoozeManager.consumeDue(context, id, now)
        }
    }
}

/** Restore AlarmManager registrations after reboot (alarms are not persisted by Android). */
class SnoozedTabBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            TabSnoozeManager.rescheduleAfterBoot(context)
        }
    }
}
