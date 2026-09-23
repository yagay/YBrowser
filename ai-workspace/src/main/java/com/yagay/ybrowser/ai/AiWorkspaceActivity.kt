package com.yagay.ybrowser.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.ui.WorkspaceRoot
import com.yagay.ybrowser.ai.ui.theme.AIHubTheme
import com.yagay.ybrowser.ai.web.WindowWebRuntime

class AiWorkspaceActivity : ComponentActivity() {
    private val webRuntime by lazy { WindowWebRuntime(this) }
    private var launchRevision by mutableIntStateOf(0)
    private var resumeRevision by mutableIntStateOf(0)
    private var webOnly by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.init(this)
        enableEdgeToEdge()
        webOnly = intent.getBooleanExtra(EXTRA_WEB_ONLY, false)
        launchRevision++
        setContent {
            AIHubTheme {
                WorkspaceRoot(
                    runtime = webRuntime,
                    launchIntent = intent,
                    launchRevision = launchRevision,
                    resumeRevision = resumeRevision,
                    webOnly = webOnly,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        webOnly = intent.getBooleanExtra(EXTRA_WEB_ONLY, false)
        launchRevision++
    }

    override fun onResume() {
        super.onResume()
        resumeRevision++
    }

    override fun onPause() {
        webRuntime.flushCookies()
        super.onPause()
    }

    companion object {
        private const val EXTRA_WEB_ONLY =
            "com.yagay.YBrowser.extra.AI_WEB_ONLY"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (!webRuntime.handleAndroidPermissionResult(
                requestCode,
                permissions,
                grantResults,
            )
        ) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
}
