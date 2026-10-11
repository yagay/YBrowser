package com.yagay.YBrowser

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

/** Discovers manifests in a background worker and pins shortcuts via the launcher. */
internal object WebAppShortcutInstaller {
    private const val ACTION_INSTALLED = "com.yagay.YBrowser.PWA_PIN_CONFIRMED"
    private const val EXTRA_ID = "webapp_id"
    private const val EXTRA_NAME = "webapp_name"
    private const val EXTRA_START = "webapp_start"
    private const val EXTRA_SCOPE = "webapp_scope"
    private const val EXTRA_MANIFEST = "webapp_manifest"
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun pin(context: Context, pageUrl: String, pageTitle: String) {
        val appContext = context.applicationContext
        val securePage = WebAppScope.canonicalHttps(pageUrl)
        val rawUri = runCatching { Uri.parse(pageUrl) }.getOrNull()
        if (securePage == null && rawUri?.scheme != "http") {
            toast(appContext, "当前页面不能添加到主屏幕")
            return
        }
        val manager = appContext.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            toast(appContext, "当前桌面不支持固定快捷方式")
            return
        }
        worker.execute {
            val entry = securePage?.let {
                WebAppManifestDiscovery.discover(it.toString(), pageTitle)
            }
            main.post {
                val shortcutId = entry?.id ?: InstalledWebAppRegistry.idFor(pageUrl)
                val launchIntent = if (entry != null) {
                    Intent(appContext, WebAppActivity::class.java).apply {
                        data = Uri.parse(entry.startUrl)
                        putExtra(WebAppActivity.EXTRA_URL, entry.startUrl)
                        putExtra(WebAppActivity.EXTRA_APP_ID, entry.id)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    }
                } else {
                    // HTTP shortcuts open in a normal browser with visible origin.
                    Intent(appContext, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_OPEN_URL
                        data = Uri.parse(pageUrl)
                        putExtra(MainActivity.EXTRA_URL, pageUrl)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                val label = (entry?.name ?: pageTitle).trim()
                    .ifBlank { rawUri?.host.orEmpty().ifBlank { "YBrowser" } }
                val shortcut = ShortcutInfo.Builder(appContext, shortcutId)
                    .setShortLabel(label.take(40))
                    .setLongLabel(label.take(80))
                    .setIcon(Icon.createWithResource(
                        appContext, android.R.drawable.ic_menu_view,
                    ))
                    .setIntent(launchIntent)
                    .build()
                // Only the launcher callback marks an installation complete.
                val callback = entry?.let { installed ->
                    val intent = Intent(appContext, WebAppPinReceiver::class.java).apply {
                        action = ACTION_INSTALLED
                        data = Uri.parse("ybrowser-webapp://pinned/" + installed.id)
                        putExtra(EXTRA_ID, installed.id)
                        putExtra(EXTRA_NAME, installed.name)
                        putExtra(EXTRA_START, installed.startUrl)
                        putExtra(EXTRA_SCOPE, installed.scope)
                        putExtra(EXTRA_MANIFEST, installed.manifestUrl.orEmpty())
                    }
                    PendingIntent.getBroadcast(
                        appContext, installed.id.hashCode(), intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ).intentSender
                }
                val accepted = runCatching {
                    manager.requestPinShortcut(shortcut, callback)
                }.getOrDefault(false)
                toast(appContext, when {
                    !accepted -> "无法添加桌面快捷方式"
                    entry?.isPwa == true -> "已发送 PWA 安装请求，请在桌面确认"
                    else -> "已发送网页快捷方式请求，请在桌面确认"
                })
            }
        }
    }

    private fun toast(context: Context, message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    internal fun confirmed(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALLED) return
        val app = InstalledWebApp(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            startUrl = intent.getStringExtra(EXTRA_START).orEmpty(),
            scope = intent.getStringExtra(EXTRA_SCOPE).orEmpty(),
            manifestUrl = intent.getStringExtra(EXTRA_MANIFEST)
                ?.takeIf(String::isNotBlank),
        )
        if (app.id.isNotBlank() && WebAppScope.contains(app.scope, app.startUrl)) {
            InstalledWebAppRegistry.save(context, app)
        }
    }
}

class WebAppPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WebAppShortcutInstaller.confirmed(context, intent)
    }
}
