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
import androidx.lifecycle.ViewModelProvider
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.ui.WorkspaceRoot
import com.yagay.ybrowser.ai.ui.WorkspaceViewModel
import com.yagay.ybrowser.ai.ui.theme.AIHubTheme
import com.yagay.ybrowser.ai.web.WindowWebRuntime

class AiWorkspaceActivity : ComponentActivity() {
    private val workspaceViewModelResult by lazy {
        runCatching {
            ViewModelProvider(
                this,
                WorkspaceViewModel.Factory(application),
            )[WorkspaceViewModel::class.java]
        }.onFailure {
            DiagnosticLogger.e(
                "WORKSPACE_BOOT",
                "viewmodel_init_failed",
                it,
            )
        }
    }

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
        runCatching {
            DiagnosticLogger.init(this)
        }
        enableEdgeToEdge()
        webOnly = intent.getBooleanExtra(EXTRA_WEB_ONLY, false)
        launchRevision++
        setContent {
            AIHubTheme {
                val runtime =
                    webRuntimeResult.getOrNull()
                val workspaceViewModel =
                    workspaceViewModelResult.getOrNull()
                if (
                    runtime != null &&
                    workspaceViewModel != null
                ) {
                    WorkspaceRoot(
                        runtime = runtime,
                        launchIntent = intent,
                        launchRevision = launchRevision,
                        resumeRevision = resumeRevision,
                        webOnly = webOnly,
                        preparedViewModel = workspaceViewModel,
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
                            if (workspaceViewModel == null) {
                                "AI 工作区本地数据初始化失败。已记录诊断信息，YBrowser 不会退出。"
                            } else {
                                "AI 工作区网页运行时初始化失败。已记录诊断信息，YBrowser 不会退出。"
                            }
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
        runCatching {
            webRuntimeResult
                .getOrNull()
                ?.flushCookies()
        }.onFailure {
            DiagnosticLogger.e(
                "WORKSPACE_BOOT",
                "pause_flush_failed",
                it,
            )
        }
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
