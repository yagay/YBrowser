package com.yagay.YBrowser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import org.json.JSONArray
import org.json.JSONObject

data class BrowserDownloadRecord(
    val id: Long,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val createdAt: Long,
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
}

object BrowserDownloadRepository {
    private const val PREFS = "ybrowser_downloads"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 300

    fun enqueue(
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
                ),
            )
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
                val id = obj.optLong("id", -1L)
                val url = obj.optString("url")
                if (id < 0L || url.isBlank()) continue
                add(
                    BrowserDownloadRecord(
                        id = id,
                        url = url,
                        fileName = obj.optString("fileName").ifBlank {
                            URLUtil.guessFileName(url, null, null)
                        },
                        mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() },
                        createdAt = obj.optLong("createdAt", 0L),
                    ),
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    fun states(context: Context): List<BrowserDownloadState> {
        val records = records(context)
        if (records.isEmpty()) return emptyList()

        val manager = context.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query()
            .setFilterById(*records.map { it.id }.toLongArray())
        val byId = mutableMapOf<Long, BrowserDownloadState>()

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
                    val record = records.firstOrNull { it.id == id } ?: continue
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
                        localUri = cursor.stringOrNull(localUriColumn)
                            ?.let(Uri::parse),
                    )
                }
            }
        }

        return records.map { record ->
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

    fun removeRecord(context: Context, id: Long) {
        saveRecords(context, records(context).filterNot { it.id == id })
    }

    fun cancelAndDelete(context: Context, id: Long) {
        runCatching {
            context.getSystemService(DownloadManager::class.java).remove(id)
        }
        removeRecord(context, id)
    }

    fun clearCompletedRecords(context: Context) {
        val completed = states(context)
            .filter {
                it.status == BrowserDownloadStatus.SUCCESS ||
                    it.status == BrowserDownloadStatus.FAILED ||
                    it.status == BrowserDownloadStatus.UNKNOWN
            }
            .map { it.record.id }
            .toSet()
        if (completed.isEmpty()) return
        saveRecords(context, records(context).filterNot { it.id in completed })
    }

    fun retry(context: Context, record: BrowserDownloadRecord): Long? {
        removeRecord(context, record.id)
        return enqueue(
            context = context,
            url = record.url,
            mimeType = record.mimeType,
        )
    }

    fun open(context: Context, state: BrowserDownloadState): Boolean {
        val manager = context.getSystemService(DownloadManager::class.java)
        val uri = manager.getUriForDownloadedFile(state.record.id)
            ?: state.localUri
            ?: return false
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
        val manager = context.getSystemService(DownloadManager::class.java)
        val uri = manager.getUriForDownloadedFile(state.record.id)
            ?: state.localUri
            ?: return false
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

    private fun addRecord(context: Context, record: BrowserDownloadRecord) {
        val merged = buildList {
            add(record)
            records(context)
                .asSequence()
                .filterNot { it.id == record.id }
                .take(MAX_RECORDS - 1)
                .forEach(::add)
        }
        saveRecords(context, merged)
    }

    private fun saveRecords(context: Context, records: List<BrowserDownloadRecord>) {
        val array = JSONArray()
        records.take(MAX_RECORDS).forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("url", record.url)
                    .put("fileName", record.fileName)
                    .put("mimeType", record.mimeType.orEmpty())
                    .put("createdAt", record.createdAt),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, array.toString())
            .apply()
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
