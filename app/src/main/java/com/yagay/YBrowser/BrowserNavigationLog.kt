package com.yagay.YBrowser

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BrowserNavigationLog {
    private const val TAG = "YBrowserNav"
    private const val FILE_NAME = "navigation-diagnostic.log"
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_LINES = 900

    @Synchronized
    fun log(
        context: Context,
        event: String,
        message: String,
    ) {
        val line =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date()) +
                " [" + event + "] " +
                message.replace('\n', ' ') +
                "\n"

        Log.d(TAG, line.trimEnd())
        runCatching {
            val file = context.applicationContext.filesDir
                .resolve(FILE_NAME)
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
    }

    @Synchronized
    fun read(context: Context): String =
        runCatching {
            context.applicationContext.filesDir
                .resolve(FILE_NAME)
                .takeIf { it.exists() }
                ?.readText(Charsets.UTF_8)
                .orEmpty()
        }.getOrDefault("")

    @Synchronized
    fun clear(context: Context) {
        runCatching {
            context.applicationContext.filesDir
                .resolve(FILE_NAME)
                .delete()
        }
    }
}
