package com.yagay.YBrowser

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.util.Locale

data class ExternalDownloadManagerApp(
    val id: String,
    val packageName: String,
    val activityName: String,
    val label: String,
    val oneDm: Boolean,
)

data class BrowserDownloadRequest(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val userAgent: String?,
    val cookies: String?,
    val referrer: String?,
)

object ExternalDownloadManager {
    private val oneDmPackages = listOf(
        "idm.internet.download.manager.plus",
        "idm.internet.download.manager",
        "idm.internet.download.manager.adm.lite",
    )

    private val genericPackages = setOf(
        "com.dv.adm",
        "com.tachibana.downloader",
    )

    private const val oneDmActivity = "idm.internet.download.manager.Downloader"

    fun discover(
        context: Context,
        request: BrowserDownloadRequest? = null,
    ): List<ExternalDownloadManagerApp> {
        val pm = context.packageManager
        val knownOneDm = oneDmPackages.mapNotNull { packageName ->
            val component = ComponentName(packageName, oneDmActivity)
            val info = runCatching {
                pm.getActivityInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0),
                )
            }.getOrNull()
                ?.takeIf { it.enabled && it.exported }
                ?: return@mapNotNull null

            ExternalDownloadManagerApp(
                id = "view|" + packageName,
                packageName = packageName,
                activityName = oneDmActivity,
                label = runCatching {
                    info.loadLabel(pm).toString()
                }.getOrDefault(packageName),
                oneDm = true,
            )
        }
        val knownPackages = knownOneDm.mapTo(hashSetOf()) { it.packageName }

        val probeMimeTypes = listOfNotNull(
            request?.mimeType,
            "application/octet-stream",
            "application/pdf",
            "application/zip",
            "video/mp4",
        ).distinct()

        val generic = probeMimeTypes.asSequence()
            .flatMap { mime ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse(request?.url ?: "https://example.com/download"),
                        mime,
                    )
                }
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(
                        PackageManager.MATCH_DEFAULT_ONLY.toLong(),
                    ),
                ).asSequence()
            }
            .filter { info ->
                val pkg = info.activityInfo?.packageName.orEmpty()
                pkg != context.packageName &&
                    pkg !in knownPackages &&
                    pkg in genericPackages
            }
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                if (!activity.enabled || !activity.exported) return@mapNotNull null
                ExternalDownloadManagerApp(
                    id = "view|" + activity.packageName,
                    packageName = activity.packageName,
                    activityName = activity.name,
                    label = runCatching {
                        info.loadLabel(pm).toString()
                    }.getOrDefault(activity.packageName),
                    oneDm = false,
                )
            }
            .distinctBy { it.packageName }
            .toList()

        return (knownOneDm + generic)
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<ExternalDownloadManagerApp> { it.oneDm }
                    .thenBy { it.label.lowercase(Locale.getDefault()) },
            )
    }

    fun find(
        context: Context,
        id: String?,
        request: BrowserDownloadRequest? = null,
    ): ExternalDownloadManagerApp? =
        id?.let { wanted ->
            discover(context, request).firstOrNull { it.id == wanted }
        }

    fun launch(
        context: Context,
        request: BrowserDownloadRequest,
        app: ExternalDownloadManagerApp,
        shareSessionData: Boolean,
    ): Boolean {
        val intent = if (app.oneDm) {
            Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(app.packageName, app.activityName)
                data = Uri.parse(request.url)
                putExtra("extra_filename", request.fileName)
                if (shareSessionData) {
                    request.cookies?.let { putExtra("extra_cookies", it) }
                    request.userAgent?.let { putExtra("extra_useragent", it) }
                    request.referrer?.let { putExtra("extra_referer", it) }
                }
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(app.packageName, app.activityName)
                setDataAndType(
                    Uri.parse(request.url),
                    request.mimeType ?: "application/octet-stream",
                )
            }
        }.apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
