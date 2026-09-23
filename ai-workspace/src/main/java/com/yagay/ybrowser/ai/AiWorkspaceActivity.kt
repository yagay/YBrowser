package com.yagay.ybrowser.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.ui.WorkspaceRoot
import com.yagay.ybrowser.ai.ui.theme.AIHubTheme
import com.yagay.ybrowser.ai.web.WindowWebRuntime

class AiWorkspaceActivity : ComponentActivity() {
    private val webRuntimeResult by lazy {
        runCatching {
            WindowWebRuntime(this)
        }.onFailure {
            DiagnosticLogger.e(
                "WORKSPACE_BOOT",
                "runtime_init_failed",
                it,
            )
        }
    }
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
                val runtime =
                    webRuntimeResult.getOrNull()
                if (runtime != null) {
                    WorkspaceRoot(
                        runtime = runtime,
                        launchIntent = intent,
                        launchRevision = launchRevision,
                        resumeRevision = resumeRevision,
                        webOnly = webOnly,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement =
                            Arrangement.Center,
                        horizontalAlignment =
                            Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "AI 工作区启动失败。已记录诊断信息，YBrowser 不会退出。"
                        )
                        Button(
                            onClick = {
                                recreate()
                            },
                            modifier =
                                Modifier.padding(
                                    top = 16.dp
                                ),
                        ) {
                            Text("重试")
                        }
                    }
                }
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
        webRuntimeResult
            .getOrNull()
            ?.flushCookies()
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
        val handled =
            webRuntimeResult
                .getOrNull()
                ?.handleAndroidPermissionResult(
                    requestCode,
                    permissions,
                    grantResults,
                )
                ?: false
        if (!handled) {
            super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults,
            )
        }
    }
}
