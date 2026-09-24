package com.yagay.ybrowser.ai

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
                WorkspaceViewModel.Factory(
                    application
                ),
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
                "runtime_wrapper_init_failed",
                it,
            )
        }
    }

    private var launchRevision by mutableIntStateOf(0)
    private var resumeRevision by mutableIntStateOf(0)
    private var workspaceLaunchIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runCatching {
            DiagnosticLogger.init(this)
        }

        enableEdgeToEdge()
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        captureWorkspaceLaunchIntent(intent)

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
                        launchIntent = workspaceLaunchIntent,
                        launchRevision =
                            launchRevision,
                        resumeRevision =
                            resumeRevision,
                        preparedViewModel =
                            workspaceViewModel,
                        onClose =
                            if (
                                isYagaYHubEmbedded(
                                    workspaceLaunchIntent
                                )
                            ) {
                                ::finish
                            } else {
                                null
                            },
                    )
                } else {
                    Box(
                        modifier =
                            Modifier.fillMaxSize(),
                        contentAlignment =
                            Alignment.Center,
                    ) {
                        Text(
                            if (
                                workspaceViewModel ==
                                    null
                            ) {
                                "AI 工作区本地数据初始化失败。已记录诊断信息，YBrowser 不会退出。"
                            } else {
                                "AI 工作区初始化失败。已记录诊断信息，YBrowser 不会退出。"
                            }
                        )
                    }
                }
            }
        }
    }

    private fun isYagaYHubEmbedded(
        intent: Intent?,
    ): Boolean {
        if (intent == null) return false

        if (
            intent.getBooleanExtra(
                AiWorkspaceContract
                    .EXTRA_YAGAYHUB_EMBEDDED,
                false,
            )
        ) {
            return true
        }

        // Backward compatibility with YagaYHub builds from before the
        // explicit embedded marker: its AI launch always carries the shared
        // targets payload.
        return intent.action ==
            AiWorkspaceContract.ACTION_OPEN_AI &&
            intent.hasExtra(
                AiWorkspaceContract
                    .EXTRA_TARGETS_JSON
            )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureWorkspaceLaunchIntent(intent)
    }

    private fun captureWorkspaceLaunchIntent(
        source: Intent?,
    ) {
        workspaceLaunchIntent =
            source?.let(::Intent)

        // The Activity task intent survives process/task recreation. Project
        // selection extras are one-shot navigation commands and must not live
        // in that retained base intent, otherwise reopening from background or
        // the generic top AI button replays an old project selection and
        // overrides WindowStore's saved active tab.
        val retained =
            source?.let(::Intent) ?: Intent()
        retained.removeExtra(
            AiWorkspaceContract.EXTRA_URL
        )
        retained.removeExtra(
            AiWorkspaceContract.EXTRA_BIND_URL
        )
        retained.removeExtra(
            AiWorkspaceContract.EXTRA_BIND_REPO
        )
        retained.removeExtra(
            AiWorkspaceContract.EXTRA_BIND_PROJECT
        )
        retained.removeExtra(
            AiWorkspaceContract.EXTRA_BIND_TITLE
        )
        retained.removeExtra(
            AiWorkspaceContract.EXTRA_WINDOW_ID
        )
        retained.removeExtra(
            AiWorkspaceContract.EXTRA_PROVIDER_ID
        )
        setIntent(retained)

        launchRevision++

        DiagnosticLogger.i(
            "WORKSPACE_BOOT",
            "launch_intent_captured action=" +
                source?.action +
                " explicitWindow=" +
                source?.getStringExtra(
                    AiWorkspaceContract.EXTRA_WINDOW_ID
                ).orEmpty().take(12) +
                " explicitRepo=" +
                source?.getStringExtra(
                    AiWorkspaceContract.EXTRA_BIND_REPO
                ).orEmpty().take(80) +
                " hasUrl=" +
                (
                    !source?.getStringExtra(
                        AiWorkspaceContract.EXTRA_URL
                    ).isNullOrBlank()
                    ),
        )
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
