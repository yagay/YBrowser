package com.yagay.YBrowser

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Record fatal Java/Kotlin crashes and recover without deleting bookmarks, tabs or profiles.
 * Recovery is consumed exactly once on the next app launch; normal sessions are unchanged.
 */
internal object BrowserCrashRecovery {
    private const val PREFS = "ybrowser_crash_guard"
    private const val KEY_CRASH_AT = "last_uncaught_crash_at"
    private const val MAX_RECOVERY_AGE_MS = 24L * 60 * 60 * 1000
    private val installed = AtomicBoolean(false)

    fun consumePreviousCrash(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastCrashAt = prefs.getLong(KEY_CRASH_AT, 0L)
        prefs.edit().remove(KEY_CRASH_AT).commit()
        return lastCrashAt > 0L &&
            System.currentTimeMillis() - lastCrashAt in 0..MAX_RECOVERY_AGE_MS
    }

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_CRASH_AT, System.currentTimeMillis()).commit()
                BrowserNavigationLog.log(
                    app,
                    "UNCAUGHT_EXCEPTION",
                    "thread=" + thread.name + " exception=" +
                        error.stackTraceToString().take(16000),
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
