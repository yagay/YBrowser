package com.yagay.YBrowser.integration.yagayhub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.yagay.YBrowser.BrowserSessionRegistry
import com.yagay.YBrowser.retainedSessionTabId

class YagaYHubBindingRemoveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // An exported broadcast's action and extras are attacker-controlled.
        // Android 14+ reports an OS-authenticated sender only when the sender
        // opts in with BroadcastOptions.setShareIdentityEnabled(true).
        // Older Android versions cannot authenticate this legacy broadcast and
        // fail closed rather than silently deleting other projects' bindings.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val uid = sentFromUid
        if (uid == Process.INVALID_UID || uid < 0) return
        if (!YagaYHubBroadcastPolicy.isTrusted(
                uid, sentFromPackage, context.packageManager.getPackagesForUid(uid)
            )
        ) return
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
