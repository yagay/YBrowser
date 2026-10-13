package com.yagay.YBrowser

import android.content.Context
import android.util.Log
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Diagnostic logs are written on one IO worker, never on the UI thread.
 * Serialized tasks preserve append/read/clear order and prevent rotation races.
 */
object BrowserNavigationLog {
    private const val TAG = "YBrowserNav"
    private const val FILE_NAME = "navigation-diagnostic.log"
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_LINES = 900
    // Diagnostics must never persist query tokens, paths, credentials or fragments.
    private val urlPattern = Regex("""(?i)https?://[^\s|\\"'<>]+""")

    private fun sanitize(message: String): String =
        urlPattern.replace(message) { match ->
            val uri = runCatching { Uri.parse(match.value) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            val host = uri?.host?.takeIf { it.isNotBlank() }
            if (scheme in setOf("http", "https") && host != null) {
                "$scheme://$host"
            } else {
                "[url-redacted]"
            }
        }
    private val diskWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ybrowser-navigation-log").apply { isDaemon = true }
    }

    fun log(context: Context, event: String, message: String) {
        val line =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) +
                " [" + event.take(48).replace('\n', ' ').replace('\r', ' ') + "] " +
                sanitize(message).take(4096).replace('\n', ' ').replace('\r', ' ') + "\n"
        Log.d(TAG, line.trimEnd())
        val app = context.applicationContext
        diskWorker.execute { runCatching { append(app, line) } }
    }

    /**
     * Fatal crashes need an on-disk record before the process dies.
     * This is only used by the uncaught exception handler; normal logs remain async.
     */
    fun logCritical(context: Context, event: String, message: String) {
        val line = "CRASH [" + event.take(48) + "] " +
            sanitize(message).take(16_000).replace('\n', ' ').replace('\r', ' ') + "\n"
        Log.e(TAG, line.trimEnd())
        runCatching {
            diskWorker.submit { append(context.applicationContext, line) }
                .get(1500, TimeUnit.MILLISECONDS)
        }
    }

    private fun append(context: Context, line: String) {
        val file = context.filesDir.resolve(FILE_NAME)
        file.appendText(line, Charsets.UTF_8)
        if (file.length() > MAX_BYTES) {
            val trimmed = file.readLines(Charsets.UTF_8)
                .takeLast(KEEP_LINES)
                .joinToString("\n")
            file.writeText(
                if (trimmed.isBlank()) "" else trimmed + "\n",
                Charsets.UTF_8,
            )
        }
    }

    /** Call from Dispatchers.IO; waits for any earlier queued appends. */
    fun read(context: Context): String =
        runCatching {
            val app = context.applicationContext
            diskWorker.submit<String> {
                app.filesDir.resolve(FILE_NAME)
                    .takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8)
                    .orEmpty()
            }.get()
        }.getOrDefault("")

    fun clear(context: Context) {
        val app = context.applicationContext
        diskWorker.execute {
            runCatching { app.filesDir.resolve(FILE_NAME).delete() }
        }
    }
}
