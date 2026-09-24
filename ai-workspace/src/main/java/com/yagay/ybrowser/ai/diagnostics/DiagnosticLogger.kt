package com.yagay.ybrowser.ai.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import android.webkit.WebView
import com.yagay.ybrowser.ai.BuildConfig
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticLogger {
    private const val TAG_PREFIX = "AIHub"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private const val MAX_SESSION_LOG_BYTES = 4L * 1024L * 1024L
    private const val MAX_SNAPSHOT_BYTES = 8L * 1024L * 1024L
    private const val MAX_BRIDGE_TRACE_BYTES = 4L * 1024L * 1024L
    private const val MAX_LOGCAT_LINES = 6000
    private val lock = Any()
    private val diskExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "AIHub-Diagnostics",
            ).apply {
                isDaemon = true
            }
        }
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    @Volatile
    private var appContext: Context? = null
    private var sessionId: String = ""
    private val noiseCounts = mutableMapOf<String, Int>()

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
                val dir = ensureDir()
                sessionId = UUID.randomUUID().toString().take(12)
                noiseCounts.clear()
                File(dir, "current-session.log").delete()
                File(dir, "web-snapshots.jsonl").delete()
                File(dir, "web-snapshots.jsonl.1").delete()
            }
        }
        recordPreviousProcessExit(context.applicationContext)
        i(
            "APP",
            "diagnostic_logger_initialized version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT} session=$sessionId"
        )
    }

    fun d(tag: String, message: String) = write("D", tag, message, null)
    fun i(tag: String, message: String) = write("I", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = write("W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    fun recordSnapshot(scope: String, phase: String, payload: String) {
        val ctx = appContext ?: return
        val safeScope = scope.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(100)
        val safePhase = phase.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(100)
        val safePayload = scrub(payload.replace('\u0000', ' '), 60_000)
        val parsedPayload = runCatching { JSONTokener(safePayload).nextValue() }.getOrNull()
        val obj = JSONObject()
            .put("timestamp", synchronized(lock) { timestampFormat.format(Date()) })
            .put("session", sessionId)
            .put("scope", safeScope)
            .put("phase", safePhase)
        if (parsedPayload != null) obj.put("payload", parsedPayload) else obj.put("payloadText", safePayload)

        diskExecutor.execute {
            runCatching {
                synchronized(lock) {
                    val file =
                        File(
                            ctx.filesDir,
                            "diagnostics/web-snapshots.jsonl",
                        )
                    if (
                        file.exists() &&
                        file.length() >=
                            MAX_SNAPSHOT_BYTES
                    ) {
                        File(
                            file.parentFile,
                            "web-snapshots.jsonl.1",
                        ).delete()
                        file.renameTo(
                            File(
                                file.parentFile,
                                "web-snapshots.jsonl.1",
                            )
                        )
                    }
                    FileOutputStream(
                        file,
                        true,
                    ).bufferedWriter(
                        Charsets.UTF_8
                    ).use { writer ->
                        writer.write(obj.toString())
                        writer.newLine()
                    }
                }
            }.onFailure { error ->
                Log.w(
                    "$TAG_PREFIX/DIAG",
                    "snapshot_write_failed scope=" +
                        safeScope +
                        " phase=" +
                        safePhase +
                        " type=" +
                        error.javaClass.simpleName,
                )
            }
        }
    }

    fun recordBridgeTrace(
        stage: String,
        provider: String = "",
        windowId: String = "",
        url: String = "",
        detail: String = "",
        candidateCount: Int = -1,
        messageCount: Int = -1,
        userCount: Int = -1,
        assistantCount: Int = -1,
    ) {
        val ctx = appContext ?: return
        val obj = JSONObject()
            .put("timestamp", synchronized(lock) { timestampFormat.format(Date()) })
            .put("session", sessionId)
            .put("stage", stage.take(80))
            .put("provider", provider.take(40))
            .put("window", windowId.take(24))
            .put("url", scrub(url, 800))
            .put("detail", scrub(detail, 1600))
            .put("candidateCount", candidateCount)
            .put("messageCount", messageCount)
            .put("userCount", userCount)
            .put("assistantCount", assistantCount)

        diskExecutor.execute {
            runCatching {
                synchronized(lock) {
                    val dir =
                        File(
                            ctx.filesDir,
                            "diagnostics",
                        ).apply {
                            mkdirs()
                        }
                    val file =
                        File(
                            dir,
                            "ai-bridge-trace.jsonl",
                        )
                    if (
                        file.exists() &&
                        file.length() >=
                            MAX_BRIDGE_TRACE_BYTES
                    ) {
                        File(
                            dir,
                            "ai-bridge-trace.jsonl.1",
                        ).delete()
                        file.renameTo(
                            File(
                                dir,
                                "ai-bridge-trace.jsonl.1",
                            )
                        )
                    }
                    FileOutputStream(
                        file,
                        true,
                    ).bufferedWriter(
                        Charsets.UTF_8
                    ).use { writer ->
                        writer.write(obj.toString())
                        writer.newLine()
                    }
                }
            }.onFailure { error ->
                Log.w(
                    "$TAG_PREFIX/BRIDGE_TRACE",
                    "trace_write_failed stage=" +
                        stage.take(80) +
                        " type=" +
                        error.javaClass.simpleName,
                )
            }
        }
    }

    fun suggestedFileName(): String = synchronized(lock) {
        "AIHub-diagnostic-${fileNameFormat.format(Date())}.zip"
    }

    fun clear() {
        flushAsyncWrites()
        val ctx = appContext ?: return
        synchronized(lock) {
            val dir = File(ctx.filesDir, "diagnostics")
            listOf(
                "aihub.log", "aihub.log.1", "current-session.log",
                "web-snapshots.jsonl", "web-snapshots.jsonl.1",
                "ai-bridge-trace.jsonl", "ai-bridge-trace.jsonl.1",
                "previous-exit-info.txt"
            ).forEach { File(dir, it).delete() }
            noiseCounts.clear()
        }
        i("APP", "diagnostic_log_cleared")
    }

    fun export(context: Context, destination: Uri): Result<Unit> = runCatching {
        init(context)
        i("EXPORT", "diagnostic_export_started")
        flushAsyncWrites()
        val resolver = context.contentResolver
        val output = resolver.openOutputStream(destination, "w")
            ?: error("Unable to open export destination")

        output.use { stream ->
            ZipOutputStream(stream.buffered()).use { zip ->
                putText(zip, "diagnostic-info.txt", buildDiagnosticInfo(context))
                val dir = File(context.filesDir, "diagnostics")
                listOf(
                    "current-session.log",
                    "ai-bridge-trace.jsonl.1",
                    "ai-bridge-trace.jsonl",
                    "web-snapshots.jsonl.1",
                    "web-snapshots.jsonl",
                    "aihub.log.1",
                    "aihub.log",
                    "previous-exit-info.txt"
                ).forEach { name ->
                    val file = File(dir, name)
                    if (file.isFile) putFile(zip, file, name)
                }
                putText(zip, "logcat.txt", collectOwnProcessLogcat())
                putText(
                    zip,
                    "README.txt",
                    "AIHub diagnostic bundle.\n" +
                        "current-session.log contains only the current app process session.\n" +
                        "ai-bridge-trace.jsonl traces Gecko/WebExtension/AI message bridge stages without message text.\n" +
                        "web-snapshots.jsonl contains privacy-safe structural WebView snapshots; message text is not exported.\n" +
                        "aihub.log may include older app sessions for historical comparison.\n" +
                        "previous-exit-info.txt records Android process-exit metadata and a scrubbed bounded trace when available.\n" +
                        "Conversations, cookies, passwords, authentication tokens and file contents are intentionally excluded.\n" +
                        "Recorded URLs are stripped of query strings/fragments where AIHub records them.\n"
                )
            }
        }
        i("EXPORT", "diagnostic_export_completed")
    }.onFailure {
        e("EXPORT", "diagnostic_export_failed type=${it.javaClass.simpleName} message=${scrub(it.message.orEmpty())}", it)
    }

    private fun flushAsyncWrites() {
        runCatching {
            diskExecutor
                .submit {}
                .get(
                    2L,
                    TimeUnit.SECONDS,
                )
        }
    }

    private fun recordPreviousProcessExit(
        context: Context,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        runCatching {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE)
                    as? ActivityManager
                    ?: return
            val prefs =
                context.getSharedPreferences(
                    "aihub_diagnostics",
                    Context.MODE_PRIVATE,
                )
            val previousTimestamp =
                prefs.getLong(
                    "last_recorded_exit_timestamp",
                    0L,
                )
            val exits =
                activityManager
                    .getHistoricalProcessExitReasons(
                        context.packageName,
                        0,
                        8,
                    )
                    .filter {
                        it.timestamp > previousTimestamp
                    }
                    .sortedByDescending {
                        it.timestamp
                    }

            if (exits.isEmpty()) return

            val newestTimestamp =
                exits.maxOf {
                    it.timestamp
                }
            val text =
                buildString {
                    exits.forEachIndexed {
                            index,
                            info,
                        ->
                        appendLine(
                            "exit[$index].timestamp=" +
                                info.timestamp
                        )
                        appendLine(
                            "exit[$index].reason=" +
                                exitReasonName(
                                    info.reason
                                )
                        )
                        appendLine(
                            "exit[$index].reasonCode=" +
                                info.reason
                        )
                        appendLine(
                            "exit[$index].status=" +
                                info.status
                        )
                        appendLine(
                            "exit[$index].importance=" +
                                info.importance
                        )
                        appendLine(
                            "exit[$index].pss=" +
                                info.pss
                        )
                        appendLine(
                            "exit[$index].rss=" +
                                info.rss
                        )
                        appendLine(
                            "exit[$index].process=" +
                                scrub(
                                    info.processName.orEmpty(),
                                    240,
                                )
                        )
                        appendLine(
                            "exit[$index].description=" +
                                scrub(
                                    info.description.orEmpty(),
                                    800,
                                )
                        )

                        val trace =
                            runCatching {
                                info.traceInputStream
                                    ?.bufferedReader()
                                    ?.use {
                                        reader ->
                                        reader.readText()
                                            .take(
                                                64 * 1024
                                            )
                                    }
                            }.getOrNull()
                                .orEmpty()
                        if (trace.isNotBlank()) {
                            appendLine(
                                "exit[$index].traceBegin"
                            )
                            appendLine(
                                scrub(
                                    trace,
                                    64 * 1024,
                                )
                            )
                            appendLine(
                                "exit[$index].traceEnd"
                            )
                        }
                        appendLine()
                    }
                }

            synchronized(lock) {
                val file =
                    File(
                        ensureDir(),
                        "previous-exit-info.txt",
                    )
                file.writeText(
                    text,
                    Charsets.UTF_8,
                )
            }
            prefs.edit()
                .putLong(
                    "last_recorded_exit_timestamp",
                    newestTimestamp,
                )
                .apply()

            val latest = exits.first()
            i(
                "EXIT_INFO",
                "previous_process_exit reason=" +
                    exitReasonName(latest.reason) +
                    " status=" + latest.status +
                    " timestamp=" +
                    latest.timestamp,
            )
        }.onFailure {
            w(
                "EXIT_INFO",
                "previous_process_exit_read_failed type=" +
                    it.javaClass.simpleName,
                it,
            )
        }
    }

    private fun exitReasonName(
        reason: Int,
    ): String =
        when (reason) {
            ApplicationExitInfo.REASON_ANR ->
                "ANR"
            ApplicationExitInfo.REASON_CRASH ->
                "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE ->
                "CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
                "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF ->
                "EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
                "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY ->
                "LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER ->
                "OTHER"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE ->
                "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED ->
                "SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED ->
                "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED ->
                "USER_STOPPED"
            else ->
                "UNKNOWN"
        }

    fun scrub(value: String, maxLength: Int = 1200): String {
        var out = value
        out = out.replace(
            Regex("(?i)(authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|id[_-]?token|token|code)=([^&\\s]+)")
        ) { match -> "${match.groupValues[1]}=<redacted>" }
        out = out.replace(
            Regex("(https?://[^\\s?#]+)(?:\\?[^\\s#]*)?(?:#[^\\s]*)?")
        ) { match -> "${match.groupValues[1]}?<redacted>" }
        if (out.length > maxLength) out = out.take(maxLength) + "…"
        return out
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val ctx = appContext ?: return
        val noiseKind = if (tag == "CONSOLE") consoleNoiseKind(message) else null
        val effectiveMessage = if (noiseKind != null) {
            val count = synchronized(lock) {
                val next = (noiseCounts[noiseKind] ?: 0) + 1
                noiseCounts[noiseKind] = next
                next
            }
            if (count != 1 && count % 25 != 0) return
            "console_noise kind=$noiseKind count=$count"
        } else message

        val safeMessage = scrub(effectiveMessage.replace('\u0000', ' '))
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40)
        val line = buildString {
            append(synchronized(lock) { timestampFormat.format(Date()) })
            append(' ')
            append(level)
            append('/')
            append(safeTag)
            append(" [")
            append(Thread.currentThread().name)
            append("] ")
            append(safeMessage)
            if (throwable != null) {
                append('\n')
                append(scrub(Log.getStackTraceString(throwable), 12000))
            }
            append('\n')
        }

        runCatching {
            synchronized(lock) {
                val dir = File(ctx.filesDir, "diagnostics").apply { mkdirs() }
                val history = File(dir, "aihub.log")
                rotateIfNeeded(history, MAX_LOG_BYTES, "aihub.log.1")
                appendText(history, line)

                val session = File(dir, "current-session.log")
                if (session.length() < MAX_SESSION_LOG_BYTES) appendText(session, line)
            }
        }

        when (level) {
            "E" -> Log.e("$TAG_PREFIX/$safeTag", safeMessage, throwable)
            "W" -> Log.w("$TAG_PREFIX/$safeTag", safeMessage, throwable)
            "I" -> Log.i("$TAG_PREFIX/$safeTag", safeMessage)
            else -> Log.d("$TAG_PREFIX/$safeTag", safeMessage)
        }
    }

    private fun consoleNoiseKind(message: String): String? = when {
        message.contains("preloaded using link preload but not used", ignoreCase = true) -> "preload-unused"
        else -> null
    }

    private fun ensureDir(): File {
        val ctx = appContext ?: error("DiagnosticLogger not initialized")
        return File(ctx.filesDir, "diagnostics").apply { mkdirs() }
    }

    private fun appendText(file: File, text: String) {
        FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(text) }
    }

    private fun rotateIfNeeded(file: File, limit: Long, previousName: String) {
        file.parentFile?.mkdirs()
        if (file.exists() && file.length() >= limit) {
            val previous = File(file.parentFile, previousName)
            previous.delete()
            file.renameTo(previous)
        }
    }

    private fun buildDiagnosticInfo(context: Context): String = buildString {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val webViewPackage = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val versionCode = if (packageInfo == null) {
            BuildConfig.VERSION_CODE.toLong()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val runtime = Runtime.getRuntime()
        appendLine("generated=${synchronized(lock) { timestampFormat.format(Date()) }}")
        appendLine("session=$sessionId")
        appendLine("package=${context.packageName}")
        appendLine("versionName=${packageInfo?.versionName ?: BuildConfig.VERSION_NAME}")
        appendLine("versionCode=$versionCode")
        appendLine("buildType=${BuildConfig.BUILD_TYPE}")
        appendLine("manufacturer=${Build.MANUFACTURER}")
        appendLine("brand=${Build.BRAND}")
        appendLine("model=${Build.MODEL}")
        appendLine("device=${Build.DEVICE}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("android=${Build.VERSION.RELEASE}")
        appendLine("fingerprint=${Build.FINGERPRINT}")
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("locales=${context.resources.configuration.locales.toLanguageTags()}")
        appendLine("timezone=${TimeZone.getDefault().id}")
        appendLine("webViewPackage=${webViewPackage?.packageName ?: "unknown"}")
        appendLine("webViewVersion=${webViewPackage?.versionName ?: "unknown"}")
        appendLine("memoryClassMb=${activityManager?.memoryClass ?: -1}")
        appendLine("largeMemoryClassMb=${activityManager?.largeMemoryClass ?: -1}")
        appendLine("lowRamDevice=${activityManager?.isLowRamDevice ?: false}")
        appendLine("runtimeMaxMemory=${runtime.maxMemory()}")
        appendLine("runtimeTotalMemory=${runtime.totalMemory()}")
        appendLine("runtimeFreeMemory=${runtime.freeMemory()}")
        synchronized(lock) {
            noiseCounts.toSortedMap().forEach { (kind, count) -> appendLine("consoleNoise.$kind=$count") }
        }
        appendLine("pid=${Process.myPid()}")
    }

    private fun collectOwnProcessLogcat(): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid=${Process.myPid()}", "-v", "threadtime")
            )
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            process.errorStream.close()
            process.destroy()
            lines.takeLast(MAX_LOGCAT_LINES).joinToString("\n") { scrub(it) } + "\n"
        }.getOrElse {
            "Unable to capture own-process logcat: ${it.javaClass.simpleName}: ${scrub(it.message.orEmpty())}\n"
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }
}
