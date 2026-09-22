package com.yagay.YBrowser.integration.yagayhub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yagay.YBrowser.BrowserSessionRegistry
import com.yagay.YBrowser.retainedSessionTabId

class YagaYHubBindingRemoveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            YagaYHubContract.ACTION_BINDING_REMOVE -> {
                val url = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_URL,
                ).orEmpty()
                if (url.isBlank()) return

                YagaYHubBindingStore(context).remove(url)
                BrowserSessionRegistry.close(
                    YagaYHubContract.RETAINED_SESSION_POOL_KEY,
                    retainedSessionTabId(url),
                )
                YagaYHubKeepAliveService.syncWithSessionPool(context)
            }

            YagaYHubContract.ACTION_BINDING_SYNC -> {
                val repo = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_REPO,
                ).orEmpty()
                val project = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_PROJECT,
                ).orEmpty()
                val url = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_URL,
                ).orEmpty()
                val title = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_TITLE,
                ).orEmpty()

                if (repo.isBlank() || url.isBlank()) return

                YagaYHubBindingStore(context).save(
                    YagaYHubBindingRecord(
                        repoKey = repo,
                        project = project.ifBlank {
                            repo.substringAfterLast('/')
                        },
                        url = url,
                        title = title.ifBlank { "AI" },
                    ),
                )
            }
        }
    }
}
