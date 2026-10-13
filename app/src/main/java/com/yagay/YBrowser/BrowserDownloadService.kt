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
import java.io.InputStream
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

    // Task ownership is per Service instance. Static task Futures survive a
    // restarted service and can prevent queued downloads from restarting.
    private val executor = Executors.newFixedThreadPool(3)
    private val tasks = ConcurrentHashMap<Long, java.util.concurrent.Future<*>>()
    private val controls = ConcurrentHashMap<Long, Control>()

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
                    discardGeckoStream(id)
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
        controls.values.forEach { it.command = Command.CANCEL }
        closeGeckoStreams()
        tasks.values.forEach { it.cancel(true) }
        executor.shutdownNow()
        tasks.clear()
        controls.clear()
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
        if (record.geckoStream) {
            val stream = geckoStreams.remove(id)
            if (stream == null) {
                // A live Gecko response cannot survive process death; don't
                // retry without cookies and accidentally save a login page.
                BrowserDownloadRepository.updateNativeState(
                    this, id, BrowserDownloadStatus.FAILED, reason = -9,
                )
                return
            }
            if (control.command != Command.RUN ||
                BrowserDownloadRepository.nativeRecord(this, id) == null
            ) {
                runCatching { stream.close() }
                return
            }
            activeGeckoStreams[id] = stream
            try {
                if (control.command == Command.RUN &&
                    BrowserDownloadRepository.nativeRecord(this, id) != null
                ) saveGeckoStream(id, control, record, tempFile, stream)
            } finally {
                activeGeckoStreams.remove(id, stream)
                runCatching { stream.close() }
            }
            return
        }
        var existingBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L
        // Never append to legacy partial files whose original server identity
        // cannot be authenticated by If-Range. Restart those safely instead.
        if (existingBytes > 0L &&
            BrowserDownloadIntegrity.resumeValidator(
                record.entityTag, record.lastModified,
            ) == null
        ) {
            existingBytes = 0L
        }

        val connection = runCatching {
            var currentUrl = record.url
            var redirects = 0
            while (true) {
                val candidate = URL(currentUrl).openConnection() as HttpURLConnection
                try {
                    // Explicit redirect handling prevents cross-origin Cookie leaks,
                    // HTTPS downgrade, and unbounded redirect chains.
                    candidate.instanceFollowRedirects = false
                    candidate.connectTimeout = 20000
                    candidate.readTimeout = 30000
                    candidate.requestMethod = "GET"
                    candidate.setRequestProperty("Accept-Encoding", "identity")
                    record.userAgent?.takeIf { it.isNotBlank() }?.let {
                        candidate.setRequestProperty("User-Agent", it)
                    }
                    if (BrowserDownloadRedirectPolicy.canForwardCookie(record.url, currentUrl)) {
                        BrowserDownloadRepository.nativeCookie(this@BrowserDownloadService, id)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { candidate.setRequestProperty("Cookie", it) }
                    }
                    if (existingBytes > 0L) {
                        candidate.setRequestProperty("Range", "bytes=" + existingBytes + "-")
                        BrowserDownloadIntegrity.resumeValidator(
                            record.entityTag, record.lastModified,
                        )?.let { candidate.setRequestProperty("If-Range", it) }
                    }
                    val responseCode = candidate.responseCode
                    if (responseCode in 300..399) {
                        if (redirects >= BrowserDownloadRedirectPolicy.MAX_REDIRECTS) {
                            error("Too many download redirects")
                        }
                        val next = BrowserDownloadRedirectPolicy.resolve(
                            currentUrl, candidate.getHeaderField("Location"),
                        ) ?: error("Unsafe download redirect")
                        candidate.disconnect()
                        currentUrl = next
                        redirects++
                        continue
                    }
                    return@runCatching candidate
                } catch (error: Exception) {
                    candidate.disconnect()
                    throw error
                }
            }
            error("Unable to open download")
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

            val decision = BrowserDownloadIntegrity.inspect(
                statusCode = code,
                alreadyDownloaded = existingBytes,
                contentRange = connection.getHeaderField("Content-Range"),
                responseLength = connection.contentLengthLong,
                previousEtag = record.entityTag,
                responseEtag = connection.getHeaderField("ETag"),
                previousLastModified = record.lastModified,
                responseLastModified = connection.getHeaderField("Last-Modified"),
            )
            if (decision is BrowserDownloadIntegrity.Decision.Reject) {
                BrowserDownloadRepository.updateNativeState(
                    this, id, BrowserDownloadStatus.FAILED,
                    bytesDownloaded = existingBytes, reason = -4,
                )
                return
            }
            decision as BrowserDownloadIntegrity.Decision.Accept
            val append = decision.append
            val totalBytes = decision.totalBytes
            if (!append) {
                existingBytes = 0L
                if (tempFile.exists()) {
                    FileOutputStream(tempFile, false).use { }
                }
            }
            BrowserDownloadRepository.updateNativeState(
                this, id, BrowserDownloadStatus.RUNNING,
                bytesDownloaded = existingBytes, totalBytes = totalBytes,
                reason = 0,
                entityTag = connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() },
                lastModified = connection.getHeaderField("Last-Modified")?.takeIf { it.isNotBlank() },
                clearValidators = !append,
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
            val finalBytes = tempFile.length()
            if (totalBytes > 0L && finalBytes != totalBytes) {
                BrowserDownloadRepository.updateNativeState(
                    this, id, BrowserDownloadStatus.FAILED,
                    bytesDownloaded = finalBytes, totalBytes = totalBytes, reason = -5,
                )
                return
            }
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

            // Cancellation can race with publishing into MediaStore. Remove
            // any late publication rather than exposing a cancelled download.
            if (control.command != Command.RUN ||
                BrowserDownloadRepository.nativeRecord(this, id) == null
            ) {
                runCatching { contentResolver.delete(uri, null, null) }
                return
            }
            tempFile.delete()
            BrowserDownloadRepository.updateNativeState(
                this,
                id,
                BrowserDownloadStatus.SUCCESS,
                bytesDownloaded = totalBytes.takeIf { it > 0L } ?: finalBytes,
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

    private fun saveGeckoStream(
        id: Long,
        control: Control,
        record: BrowserDownloadRecord,
        tempFile: File,
        source: InputStream,
    ) {
        try {
            source.use { input ->
                val total = record.nativeTotalBytes
                var downloaded = 0L
                var lastPersistAt = 0L
                BrowserDownloadRepository.updateNativeState(
                    this, id, BrowserDownloadStatus.RUNNING,
                    bytesDownloaded = 0L, totalBytes = total,
                )
                FileOutputStream(tempFile, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (control.command != Command.RUN ||
                            BrowserDownloadRepository.nativeRecord(this, id) == null
                        ) return
                        val length = input.read(buffer)
                        if (length < 0) break
                        if (length == 0) continue
                        output.write(buffer, 0, length)
                        downloaded += length
                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt > 500L) {
                            lastPersistAt = now
                            BrowserDownloadRepository.updateNativeState(
                                this, id, BrowserDownloadStatus.RUNNING,
                                bytesDownloaded = downloaded,
                            )
                            updateForeground()
                        }
                    }
                    output.flush()
                }
                if (control.command != Command.RUN ||
                    BrowserDownloadRepository.nativeRecord(this, id) == null
                ) return
                val finalSize = tempFile.length()
                if (total > 0L && finalSize != total) {
                    BrowserDownloadRepository.updateNativeState(
                        this, id, BrowserDownloadStatus.FAILED,
                        bytesDownloaded = finalSize, reason = -5,
                    )
                    return
                }
                val latest = BrowserDownloadRepository.nativeRecord(this, id)
                    ?: return
                val uri = BrowserDownloadRepository.publishCompletedFile(
                    this, latest, tempFile,
                ) ?: run {
                    BrowserDownloadRepository.updateNativeState(
                        this, id, BrowserDownloadStatus.FAILED, reason = -3,
                    )
                    return
                }
                if (control.command != Command.RUN ||
                    BrowserDownloadRepository.nativeRecord(this, id) == null
                ) {
                    runCatching { contentResolver.delete(uri, null, null) }
                    return
                }
                tempFile.delete()
                BrowserDownloadRepository.updateNativeState(
                    this, id, BrowserDownloadStatus.SUCCESS,
                    bytesDownloaded = finalSize, totalBytes = total,
                    localUri = uri,
                )
            }
        } catch (_: Exception) {
            if (control.command != Command.CANCEL &&
                BrowserDownloadRepository.nativeRecord(this, id) != null
            ) {
                BrowserDownloadRepository.updateNativeState(
                    this, id, BrowserDownloadStatus.FAILED,
                    bytesDownloaded = tempFile.length(), reason = -2,
                )
            }
        }
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

        private val geckoStreams = ConcurrentHashMap<Long, InputStream>()
        private val activeGeckoStreams = ConcurrentHashMap<Long, InputStream>()

        fun registerGeckoStream(id: Long, body: InputStream) {
            geckoStreams.put(id, body)?.let { runCatching { it.close() } }
        }

        fun discardGeckoStream(id: Long) {
            geckoStreams.remove(id)?.let { runCatching { it.close() } }
            activeGeckoStreams[id]?.let { runCatching { it.close() } }
        }

        private fun closeGeckoStreams() {
            (geckoStreams.values + activeGeckoStreams.values).forEach {
                runCatching { it.close() }
            }
            geckoStreams.clear()
            activeGeckoStreams.clear()
        }

        fun start(context: Context, id: Long) =
            send(context, ACTION_START, id)

        fun pause(context: Context, id: Long) =
            send(context, ACTION_PAUSE, id)

        fun resume(context: Context, id: Long) =
            send(context, ACTION_RESUME, id)

        fun cancel(context: Context, id: Long) {
            discardGeckoStream(id)
            send(context, ACTION_CANCEL, id)
        }

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
