package com.yagay.ybrowser.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.ui.WorkspaceRoot
import com.yagay.ybrowser.ai.ui.theme.AIHubTheme
import com.yagay.ybrowser.ai.web.WindowWebRuntime

class AiWorkspaceActivity : ComponentActivity() {
    private val webRuntime by lazy { WindowWebRuntime(this) }
    private var launchRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.init(this)
        enableEdgeToEdge()
        launchRevision++
        setContent {
            AIHubTheme {
                WorkspaceRoot(
                    runtime = webRuntime,
                    launchIntent = intent,
                    launchRevision = launchRevision,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRevision++
    }

    override fun onPause() {
        webRuntime.flushCookies()
        super.onPause()
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
