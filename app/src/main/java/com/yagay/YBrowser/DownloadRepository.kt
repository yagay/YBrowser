package com.yagay.YBrowser

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class BrowserDownloadBackend {
    SYSTEM,
    NATIVE,
}

data class BrowserDownloadRecord(
    val id: Long,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val createdAt: Long,
    val backend: BrowserDownloadBackend = BrowserDownloadBackend.SYSTEM,
    val userAgent: String? = null,
    val nativeStatus: BrowserDownloadStatus? = null,
    val nativeBytesDownloaded: Long = 0L,
    val nativeTotalBytes: Long = -1L,
    val nativeReason: Int = 0,
    val localUriString: String? = null,
    val tempPath: String? = null,
)

enum class BrowserDownloadStatus {
    PENDING,
    RUNNING,
    PAUSED,
    SUCCESS,
    FAILED,
    UNKNOWN,
}

data class BrowserDownloadState(
    val record: BrowserDownloadRecord,
    val status: BrowserDownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val reason: Int,
    val localUri: Uri?,
) {
    val progress: Float?
        get() = if (totalBytes > 0L) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    val pausable: Boolean
        get() = record.backend == BrowserDownloadBackend.NATIVE &&
            (status == BrowserDownloadStatus.RUNNING ||
                status == BrowserDownloadStatus.PENDING)

    val resumable: Boolean
        get() = record.backend == BrowserDownloadBackend.NATIVE &&
            (status == BrowserDownloadStatus.PAUSED ||
                status == BrowserDownloadStatus.FAILED)
}

object BrowserDownloadRepository {
    private const val PREFS = "ybrowser_downloads"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 300
    private const val COOKIE_SECRET_PREFIX = "download_cookie_"

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        cookie: String? = null,
    ): Long? = enqueueSystem(
        context = context,
        url = url,
        userAgent = userAgent,
        contentDisposition = contentDisposition,
        mimeType = mimeType,
        cookie = cookie,
    )

    fun enqueueSystem(
        context: Context,
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        cookie: String? = null,
    ): Long? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            removeSameName(context, fileName)

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("YBrowser")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            val effectiveCookie = cookie ?: runCatching {
                CookieManager.getInstance().getCookie(url)
            }.getOrNull()
            if (!effectiveCookie.isNullOrBlank()) {
                request.addRequestHeader("Cookie", effectiveCookie)
            }

            val manager = context.getSystemService(DownloadManager::class.java)
            val id = manager.enqueue(request)
            addRecord(
                context,
                BrowserDownloadRecord(
                    id = id,
                    url = url,
                    fileName = fileName,
                    mimeType = mimeType,
                    createdAt = System.currentTimeMillis(),
                    backend = BrowserDownloadBackend.SYSTEM,
                ),
            )
            id
        }.getOrNull()
    }

    fun enqueueNative(
        context: Context,
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        cookie: String? = null,
    ): Long? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            removeSameName(context, fileName)

            val id = newNativeId()
            val tempDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                ".ybrowser",
            ).apply { mkdirs() }
            val tempFile = File(tempDir, id.toString() + ".part")
            val effectiveCookie = cookie ?: runCatching {
                CookieManager.getInstance().getCookie(url)
            }.getOrNull()
            if (!effectiveCookie.isNullOrBlank()) {
                BrowserSecretStore.put(
                    context,
                    COOKIE_SECRET_PREFIX + id,
                    effectiveCookie,
                )
            }
            addRecord(
                context,
                BrowserDownloadRecord(
                    id = id,
                    url = url,
                    fileName = fileName,
                    mimeType = mimeType,
                    createdAt = System.currentTimeMillis(),
                    backend = BrowserDownloadBackend.NATIVE,
                    userAgent = userAgent,
                    nativeStatus = BrowserDownloadStatus.PENDING,
                    tempPath = tempFile.absolutePath,
                ),
            )
            BrowserDownloadService.start(context, id)
            id
        }.getOrNull()
    }

    fun records(context: Context): List<BrowserDownloadRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null)
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optLong("id", Long.MIN_VALUE)
                val url = obj.optString("url")
                if (id == Long.MIN_VALUE || url.isBlank()) continue
                val backend = runCatching {
                    BrowserDownloadBackend.valueOf(
                        obj.optString("backend", BrowserDownloadBackend.SYSTEM.name)
                    )
                }.getOrDefault(BrowserDownloadBackend.SYSTEM)
                val nativeStatus = obj.optString("nativeStatus")
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        runCatching { BrowserDownloadStatus.valueOf(it) }.getOrNull()
                    }
                add(
                    BrowserDownloadRecord(
                        id = id,
                        url = url,
                        fileName = obj.optString("fileName").ifBlank {
                            URLUtil.guessFileName(url, null, null)
                        },
                        mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() },
                        createdAt = obj.optLong("createdAt", 0L),
                        backend = backend,
                        userAgent = obj.optString("userAgent").takeIf { it.isNotBlank() },
                        nativeStatus = nativeStatus,
                        nativeBytesDownloaded = obj.optLong("nativeBytesDownloaded", 0L),
                        nativeTotalBytes = obj.optLong("nativeTotalBytes", -1L),
                        nativeReason = obj.optInt("nativeReason", 0),
                        localUriString = obj.optString("localUri").takeIf { it.isNotBlank() },
                        tempPath = obj.optString("tempPath").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    fun nativeRecord(context: Context, id: Long): BrowserDownloadRecord? =
        records(context).firstOrNull {
            it.id == id && it.backend == BrowserDownloadBackend.NATIVE
        }

    fun nativeActiveRecords(context: Context): List<BrowserDownloadRecord> =
        records(context).filter {
            it.backend == BrowserDownloadBackend.NATIVE &&
                (it.nativeStatus == BrowserDownloadStatus.PENDING ||
                    it.nativeStatus == BrowserDownloadStatus.RUNNING)
        }

    fun nativeCookie(context: Context, id: Long): String? =
        BrowserSecretStore.get(context, COOKIE_SECRET_PREFIX + id)

    @Synchronized
    fun updateNativeState(
        context: Context,
        id: Long,
        status: BrowserDownloadStatus,
        bytesDownloaded: Long? = null,
        totalBytes: Long? = null,
        reason: Int? = null,
        localUri: Uri? = null,
    ) {
        val updated = records(context).map { record ->
            if (record.id != id || record.backend != BrowserDownloadBackend.NATIVE) {
                record
            } else {
                record.copy(
                    nativeStatus = status,
                    nativeBytesDownloaded =
                        bytesDownloaded ?: record.nativeBytesDownloaded,
                    nativeTotalBytes = totalBytes ?: record.nativeTotalBytes,
                    nativeReason = reason ?: record.nativeReason,
                    localUriString = localUri?.toString() ?: record.localUriString,
                )
            }
        }
        saveRecords(context, updated)
        if (status == BrowserDownloadStatus.SUCCESS) {
            BrowserSecretStore.remove(context, COOKIE_SECRET_PREFIX + id)
        }
    }

    fun states(context: Context): List<BrowserDownloadState> {
        val records = records(context)
        if (records.isEmpty()) return emptyList()

        val systemRecords = records.filter {
            it.backend == BrowserDownloadBackend.SYSTEM && it.id >= 0L
        }
        val byId = mutableMapOf<Long, BrowserDownloadState>()

        if (systemRecords.isNotEmpty()) {
            val manager = context.getSystemService(DownloadManager::class.java)
            val query = DownloadManager.Query()
                .setFilterById(*systemRecords.map { it.id }.toLongArray())
            runCatching {
                manager.query(query)?.use { cursor ->
                    val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                    val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val currentColumn = cursor.getColumnIndex(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                    )
                    val totalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val reasonColumn = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val localUriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

                    while (cursor.moveToNext()) {
                        val id = cursor.longOrDefault(idColumn, -1L)
                        val record = systemRecords.firstOrNull { it.id == id } ?: continue
                        val statusRaw = cursor.intOrDefault(
                            statusColumn,
                            DownloadManager.STATUS_FAILED,
                        )
                        byId[id] = BrowserDownloadState(
                            record = record,
                            status = statusRaw.toBrowserStatus(),
                            bytesDownloaded = cursor.longOrDefault(currentColumn, 0L),
                            totalBytes = cursor.longOrDefault(totalColumn, -1L),
                            reason = cursor.intOrDefault(reasonColumn, 0),
                            localUri = cursor.stringOrNull(localUriColumn)?.let(Uri::parse),
                        )
                    }
                }
            }
        }

        return records.map { record ->
            if (record.backend == BrowserDownloadBackend.NATIVE) {
                BrowserDownloadState(
                    record = record,
                    status = record.nativeStatus ?: BrowserDownloadStatus.UNKNOWN,
                    bytesDownloaded = record.nativeBytesDownloaded,
                    totalBytes = record.nativeTotalBytes,
                    reason = record.nativeReason,
                    localUri = record.localUriString?.let(Uri::parse),
                )
            } else {
                byId[record.id] ?: BrowserDownloadState(
                    record = record,
                    status = BrowserDownloadStatus.UNKNOWN,
                    bytesDownloaded = 0L,
                    totalBytes = -1L,
                    reason = 0,
                    localUri = null,
                )
            }
        }
    }

    fun pause(context: Context, id: Long): Boolean {
        val record = nativeRecord(context, id) ?: return false
        if (
            record.nativeStatus != BrowserDownloadStatus.RUNNING &&
            record.nativeStatus != BrowserDownloadStatus.PENDING
        ) return false
        updateNativeState(
            context,
            id,
            BrowserDownloadStatus.PAUSED,
            bytesDownloaded = record.nativeBytesDownloaded,
            totalBytes = record.nativeTotalBytes,
        )
        BrowserDownloadService.pause(context, id)
        return true
    }

    fun resume(context: Context, id: Long): Boolean {
        val record = nativeRecord(context, id) ?: return false
        if (
            record.nativeStatus != BrowserDownloadStatus.PAUSED &&
            record.nativeStatus != BrowserDownloadStatus.FAILED
        ) return false
        updateNativeState(
            context,
            id,
            BrowserDownloadStatus.PENDING,
            reason = 0,
        )
        BrowserDownloadService.resume(context, id)
        return true
    }

    fun removeRecord(context: Context, id: Long) {
        saveRecords(context, records(context).filterNot { it.id == id })
        BrowserSecretStore.remove(context, COOKIE_SECRET_PREFIX + id)
    }

    fun cancelAndDelete(context: Context, id: Long) {
        val record = records(context).firstOrNull { it.id == id }
        if (record?.backend == BrowserDownloadBackend.NATIVE) {
            BrowserDownloadService.cancel(context, id)
            deleteNativeData(context, record, deletePublished = true)
        } else {
            runCatching {
                context.getSystemService(DownloadManager::class.java).remove(id)
            }
        }
        removeRecord(context, id)
    }

    fun clearCompletedRecords(context: Context) {
        val stateById = states(context).associateBy { it.record.id }
        val removable = records(context).filter { record ->
            val status = stateById[record.id]?.status ?: BrowserDownloadStatus.UNKNOWN
            status == BrowserDownloadStatus.SUCCESS ||
                status == BrowserDownloadStatus.FAILED ||
                status == BrowserDownloadStatus.UNKNOWN
        }
        removable.forEach { record ->
            if (
                record.backend == BrowserDownloadBackend.NATIVE &&
                record.nativeStatus != BrowserDownloadStatus.SUCCESS
            ) {
                deleteNativeData(context, record, deletePublished = false)
            }
            BrowserSecretStore.remove(context, COOKIE_SECRET_PREFIX + record.id)
        }
        val ids = removable.map { it.id }.toSet()
        if (ids.isNotEmpty()) {
            saveRecords(context, records(context).filterNot { it.id in ids })
        }
    }

    fun retry(context: Context, record: BrowserDownloadRecord): Long? {
        return if (record.backend == BrowserDownloadBackend.NATIVE) {
            if (resume(context, record.id)) record.id else null
        } else {
            cancelAndDelete(context, record.id)
            enqueueSystem(
                context = context,
                url = record.url,
                mimeType = record.mimeType,
            )
        }
    }

    fun open(context: Context, state: BrowserDownloadState): Boolean {
        val uri = if (state.record.backend == BrowserDownloadBackend.NATIVE) {
            state.localUri
        } else {
            context.getSystemService(DownloadManager::class.java)
                .getUriForDownloadedFile(state.record.id)
                ?: state.localUri
        } ?: return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, state.record.mimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)
    }

    fun share(context: Context, state: BrowserDownloadState): Boolean {
        val uri = if (state.record.backend == BrowserDownloadBackend.NATIVE) {
            state.localUri
        } else {
            context.getSystemService(DownloadManager::class.java)
                .getUriForDownloadedFile(state.record.id)
                ?: state.localUri
        } ?: return false
        return runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = state.record.mimeType ?: "*/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "分享下载文件",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    fun publishCompletedFile(
        context: Context,
        record: BrowserDownloadRecord,
        tempFile: File,
    ): Uri? {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, record.fileName)
            put(
                MediaStore.Downloads.MIME_TYPE,
                record.mimeType ?: "application/octet-stream",
            )
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS,
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return null

        return try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            } ?: error("无法写入下载文件")
            val ready = android.content.ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, ready, null, null)
            uri
        } catch (error: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            null
        }
    }

    private fun removeSameName(context: Context, fileName: String) {
        records(context)
            .filter { it.fileName.equals(fileName, ignoreCase = true) }
            .forEach { record ->
                if (record.backend == BrowserDownloadBackend.NATIVE) {
                    BrowserDownloadService.cancel(context, record.id)
                    deleteNativeData(context, record, deletePublished = true)
                } else {
                    runCatching {
                        context.getSystemService(DownloadManager::class.java)
                            .remove(record.id)
                    }
                }
                BrowserSecretStore.remove(context, COOKIE_SECRET_PREFIX + record.id)
            }
        saveRecords(
            context,
            records(context).filterNot {
                it.fileName.equals(fileName, ignoreCase = true)
            },
        )
    }

    private fun deleteNativeData(
        context: Context,
        record: BrowserDownloadRecord,
        deletePublished: Boolean,
    ) {
        record.tempPath?.let { path ->
            runCatching { File(path).delete() }
        }
        if (deletePublished) {
            record.localUriString?.let { raw ->
                runCatching {
                    context.contentResolver.delete(Uri.parse(raw), null, null)
                }
            }
        }
    }

    @Synchronized
    private fun addRecord(context: Context, record: BrowserDownloadRecord) {
        val merged = buildList {
            add(record)
            records(context)
                .asSequence()
                .filterNot {
                    it.id == record.id ||
                        it.fileName.equals(record.fileName, ignoreCase = true)
                }
                .take(MAX_RECORDS - 1)
                .forEach(::add)
        }
        saveRecords(context, merged)
    }

    @Synchronized
    private fun saveRecords(context: Context, records: List<BrowserDownloadRecord>) {
        val array = JSONArray()
        records.take(MAX_RECORDS).forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("url", record.url)
                    .put("fileName", record.fileName)
                    .put("mimeType", record.mimeType.orEmpty())
                    .put("createdAt", record.createdAt)
                    .put("backend", record.backend.name)
                    .put("userAgent", record.userAgent.orEmpty())
                    .put("nativeStatus", record.nativeStatus?.name.orEmpty())
                    .put("nativeBytesDownloaded", record.nativeBytesDownloaded)
                    .put("nativeTotalBytes", record.nativeTotalBytes)
                    .put("nativeReason", record.nativeReason)
                    .put("localUri", record.localUriString.orEmpty())
                    .put("tempPath", record.tempPath.orEmpty()),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, array.toString())
            .apply()
    }

    private fun newNativeId(): Long {
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        return -now
    }

    private fun Int.toBrowserStatus(): BrowserDownloadStatus = when (this) {
        DownloadManager.STATUS_PENDING -> BrowserDownloadStatus.PENDING
        DownloadManager.STATUS_RUNNING -> BrowserDownloadStatus.RUNNING
        DownloadManager.STATUS_PAUSED -> BrowserDownloadStatus.PAUSED
        DownloadManager.STATUS_SUCCESSFUL -> BrowserDownloadStatus.SUCCESS
        DownloadManager.STATUS_FAILED -> BrowserDownloadStatus.FAILED
        else -> BrowserDownloadStatus.UNKNOWN
    }

    private fun Cursor.longOrDefault(column: Int, fallback: Long): Long =
        if (column >= 0 && !isNull(column)) getLong(column) else fallback

    private fun Cursor.intOrDefault(column: Int, fallback: Int): Int =
        if (column >= 0 && !isNull(column)) getInt(column) else fallback

    private fun Cursor.stringOrNull(column: Int): String? =
        if (column >= 0 && !isNull(column)) getString(column) else null
}
