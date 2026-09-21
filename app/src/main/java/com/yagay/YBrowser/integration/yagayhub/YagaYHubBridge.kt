package com.yagay.YBrowser.integration.yagayhub

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.yagay.YBrowser.BrowserBindingController
import com.yagay.YBrowser.BrowserPageBinding
import com.yagay.YBrowser.BrowserSessionRegistry
import com.yagay.YBrowser.retainedSessionTabId

object YagaYHubBridge {
    fun bindingController(
        context: Context,
        revision: Int,
        targetRepo: String?,
        targetProject: String?,
        onBound: (String, String) -> Unit = { _, _ -> },
    ): BrowserBindingController {
        val store = YagaYHubBindingStore(context)
        val targetLabel = targetRepo
            ?.takeIf { it.isNotBlank() }
            ?.let { repo ->
                targetProject.orEmpty()
                    .ifBlank { repo.substringAfterLast('/') }
            }

        return BrowserBindingController(
            revision = revision,
            targetLabel = targetLabel,
            findBinding = { url ->
                store.find(url)?.let {
                    BrowserPageBinding(it.project)
                }
            },
            bindToTarget = { url, title ->
                val repo = targetRepo.orEmpty()
                if (repo.isNotBlank()) {
                    store.save(
                        YagaYHubBindingRecord(
                            repoKey = repo,
                            project = targetProject.orEmpty()
                                .ifBlank { repo.substringAfterLast('/') },
                            url = url,
                            title = title,
                        ),
                    )
                    onBound(url, title)
                }
            },
            unbind = { url ->
                val existing = store.find(url)
                store.remove(url)
                BrowserSessionRegistry.close(
                    YagaYHubContract.RETAINED_SESSION_POOL_KEY,
                    retainedSessionTabId(url),
                )
                YagaYHubKeepAliveService.syncWithSessionPool(context)
                notifyBindingRemoved(context, url)
                existing?.let {
                    Toast.makeText(
                        context,
                        "已取消绑定 · " + it.project,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            requestBinding = { url, title ->
                requestBindingPicker(context, url, title)
            },
        )
    }

    fun requestBindingPicker(
        context: Context,
        url: String,
        title: String,
    ) {
        if (url.isBlank()) return
        val intent = Intent(YagaYHubContract.ACTION_REQUEST_BINDING).apply {
            setPackage(YagaYHubContract.PACKAGE)
            putExtra(YagaYHubContract.EXTRA_BIND_URL, url)
            putExtra(
                YagaYHubContract.EXTRA_BIND_TITLE,
                title.ifBlank { "AI" },
            )
            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(
                    context,
                    "请先安装或更新 YagaYHub",
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    fun notifyBindingRemoved(
        context: Context,
        url: String,
    ) {
        if (url.isBlank()) return
        val intent = Intent(
            YagaYHubContract.ACTION_NOTIFY_BINDING_REMOVE,
        ).apply {
            setPackage(YagaYHubContract.PACKAGE)
            putExtra(YagaYHubContract.EXTRA_BIND_URL, url)
        }
        runCatching { context.sendBroadcast(intent) }
    }

    fun sendBindingResult(
        context: Context,
        repo: String,
        project: String,
        url: String,
        title: String,
    ) {
        if (repo.isBlank() || url.isBlank()) return
        val intent = Intent(
            YagaYHubContract.ACTION_BINDING_RESULT,
        ).apply {
            setPackage(YagaYHubContract.PACKAGE)
            putExtra(YagaYHubContract.EXTRA_BIND_REPO, repo)
            putExtra(YagaYHubContract.EXTRA_BIND_PROJECT, project)
            putExtra(YagaYHubContract.EXTRA_BIND_URL, url)
            putExtra(YagaYHubContract.EXTRA_BIND_TITLE, title)
        }
        runCatching { context.sendBroadcast(intent) }
    }

    fun openBindingResultActivity(
        context: Context,
        repo: String,
        project: String,
        url: String,
        title: String,
    ) {
        if (repo.isBlank() || url.isBlank()) return
        val intent = Intent(
            YagaYHubContract.ACTION_BINDING_RESULT,
        ).apply {
            setPackage(YagaYHubContract.PACKAGE)
            putExtra(YagaYHubContract.EXTRA_BIND_REPO, repo)
            putExtra(YagaYHubContract.EXTRA_BIND_PROJECT, project)
            putExtra(YagaYHubContract.EXTRA_BIND_URL, url)
            putExtra(YagaYHubContract.EXTRA_BIND_TITLE, title)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { context.startActivity(intent) }
    }
}
