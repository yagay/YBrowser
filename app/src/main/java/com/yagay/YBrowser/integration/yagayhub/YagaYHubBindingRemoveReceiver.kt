package com.yagay.YBrowser.integration.yagayhub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yagay.YBrowser.BrowserSessionRegistry
import com.yagay.YBrowser.retainedSessionTabId

class YagaYHubBindingRemoveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != YagaYHubContract.ACTION_BINDING_REMOVE) {
            return
        }
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
}
