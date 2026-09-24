package com.yagay.YBrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class BrowserDownloadService : Service() {
    private enum class Command {
        RUN,
        PAUSE,
        CANCEL,
    }

    private class Control {
        @Volatile
        var command: Command = Command.RUN
    }

    private val executor = Executors.newFixedThreadPool(3)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("准备下载"))
        BrowserDownloadRepository.nativeActiveRecords(this).forEach {
            startTask(it.id)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val id = intent?.getLongExtra(EXTRA_ID, Long.MIN_VALUE)
            ?: Long.MIN_VALUE
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                if (id != Long.MIN_VALUE) {
                    controls.computeIfAbsent(id) { Control() }.command = Command.RUN
                    startTask(id)
                }
            }
            ACTION_PAUSE -> {
                if (id != Long.MIN_VALUE) {
                    controls.computeIfAbsent(id) { Control() }.command = Command.PAUSE
                }
            }
            ACTION_CANCEL -> {
                if (id != Long.MIN_VALUE) {
                    controls.computeIfAbsent(id) { Control() }.command = Command.CANCEL
                }
            }
        }
        updateForeground()
        if (
            tasks.isEmpty() &&
            (intent?.action == ACTION_PAUSE || intent?.action == ACTION_CANCEL)
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTask(id: Long) {
        synchronized(tasks) {
            val existing = tasks[id]
            if (existing != null && !existing.isDone) return
            val control = controls.computeIfAbsent(id) { Control() }
            control.command = Command.RUN
            tasks[id] = executor.submit {
                try {
                    download(id, control)
                } finally {
                    tasks.remove(id)
                    controls.remove(id)
                    updateForeground()
                    if (tasks.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun download(
        id: Long,
        control: Control,
    ) {
        val record = BrowserDownloadRepository.nativeRecord(this, id) ?: return
        val path = record.tempPath ?: return
        val tempFile = File(path).apply {
            parentFile?.mkdirs()
        }
        var existingBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L

        val connection = runCatching {
            (URL(record.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 30000
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                record.userAgent?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("User-Agent", it)
                }
                BrowserDownloadRepository.nativeCookie(this@BrowserDownloadService, id)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
                if (existingBytes > 0L) {
                    setRequestProperty("Range", "bytes=" + existingBytes + "-")
                }
            }
        }.getOrElse {
            BrowserDownloadRepository.updateNativeState(
                this,
                id,
                BrowserDownloadStatus.FAILED,
                reason = -1,
            )
            return
        }

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK &&
                code != HttpURLConnection.HTTP_PARTIAL
            ) {
                BrowserDownloadRepository.updateNativeState(
                    this,
                    id,
                    BrowserDownloadStatus.FAILED,
                    reason = code,
                )
                return
            }

            val append = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            if (!append) {
                existingBytes = 0L
                if (tempFile.exists()) {
                    FileOutputStream(tempFile, false).use { }
                }
            }

            val totalBytes = contentTotalBytes(
                connection = connection,
                existingBytes = existingBytes,
                partial = append,
            )

            BrowserDownloadRepository.updateNativeState(
                this,
                id,
                BrowserDownloadStatus.RUNNING,
                bytesDownloaded = existingBytes,
                totalBytes = totalBytes,
                reason = 0,
            )
            updateForeground()

            var downloaded = existingBytes
            var lastPersistAt = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tempFile, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        when (control.command) {
                            Command.PAUSE -> {
                                BrowserDownloadRepository.updateNativeState(
                                    this,
                                    id,
                                    BrowserDownloadStatus.PAUSED,
                                    bytesDownloaded = downloaded,
                                    totalBytes = totalBytes,
                                )
                                return
                            }
                            Command.CANCEL -> return
                            Command.RUN -> Unit
                        }

                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count

                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= 500L) {
                            lastPersistAt = now
                            BrowserDownloadRepository.updateNativeState(
                                this,
                                id,
                                BrowserDownloadStatus.RUNNING,
                                bytesDownloaded = downloaded,
                                totalBytes = totalBytes,
                            )
                            updateForeground()
                        }
                    }
                    output.flush()
                }
            }

            when (control.command) {
                Command.PAUSE -> {
                    BrowserDownloadRepository.updateNativeState(
                        this,
                        id,
                        BrowserDownloadStatus.PAUSED,
                        bytesDownloaded = tempFile.length(),
                        totalBytes = totalBytes,
                    )
                    return
                }
                Command.CANCEL -> return
                Command.RUN -> Unit
            }

            val latest = BrowserDownloadRepository.nativeRecord(this, id) ?: return
            val uri = BrowserDownloadRepository.publishCompletedFile(
                this,
                latest,
                tempFile,
            )
            if (uri == null) {
                BrowserDownloadRepository.updateNativeState(
                    this,
                    id,
                    BrowserDownloadStatus.FAILED,
                    bytesDownloaded = tempFile.length(),
                    totalBytes = totalBytes,
                    reason = -3,
                )
                return
            }

            tempFile.delete()
            BrowserDownloadRepository.updateNativeState(
                this,
                id,
                BrowserDownloadStatus.SUCCESS,
                bytesDownloaded = totalBytes.takeIf { it > 0L } ?: latest.nativeBytesDownloaded,
                totalBytes = totalBytes,
                reason = 0,
                localUri = uri,
            )
        } catch (error: Exception) {
            if (control.command == Command.PAUSE) {
                BrowserDownloadRepository.updateNativeState(
                    this,
                    id,
                    BrowserDownloadStatus.PAUSED,
                    bytesDownloaded = tempFile.length(),
                    reason = 0,
                )
            } else if (control.command != Command.CANCEL) {
                BrowserDownloadRepository.updateNativeState(
                    this,
                    id,
                    BrowserDownloadStatus.FAILED,
                    bytesDownloaded = tempFile.length(),
                    reason = -2,
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun contentTotalBytes(
        connection: HttpURLConnection,
        existingBytes: Long,
        partial: Boolean,
    ): Long {
        if (partial) {
            val range = connection.getHeaderField("Content-Range").orEmpty()
            Regex("/(\\d+)$").find(range)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.let { return it }
        }
        val length = connection.contentLengthLong
        if (length <= 0L) return -1L
        return if (partial) existingBytes + length else length
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "YBrowser 下载",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YBrowser 下载")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateForeground() {
        val active = tasks.keys.size
        if (active <= 0) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification("正在下载 " + active + " 个文件"),
        )
    }

    companion object {
        private const val CHANNEL_ID = "ybrowser_downloads"
        private const val NOTIFICATION_ID = 2103
        private const val EXTRA_ID = "download_id"
        private const val ACTION_START = "com.yagay.YBrowser.download.START"
        private const val ACTION_PAUSE = "com.yagay.YBrowser.download.PAUSE"
        private const val ACTION_RESUME = "com.yagay.YBrowser.download.RESUME"
        private const val ACTION_CANCEL = "com.yagay.YBrowser.download.CANCEL"

        private val tasks = ConcurrentHashMap<Long, java.util.concurrent.Future<*>>()
        private val controls = ConcurrentHashMap<Long, Control>()

        fun start(context: Context, id: Long) =
            send(context, ACTION_START, id)

        fun pause(context: Context, id: Long) =
            send(context, ACTION_PAUSE, id)

        fun resume(context: Context, id: Long) =
            send(context, ACTION_RESUME, id)

        fun cancel(context: Context, id: Long) =
            send(context, ACTION_CANCEL, id)

        fun recover(context: Context) {
            BrowserDownloadRepository.nativeActiveRecords(context).forEach {
                start(context, it.id)
            }
        }

        private fun send(
            context: Context,
            action: String,
            id: Long,
        ) {
            val intent = Intent(context, BrowserDownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ID, id)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
