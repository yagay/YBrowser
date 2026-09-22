package com.yagay.browsercore

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime

object SharedGeckoRuntime {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        return synchronized(this) {
            runtime ?: GeckoRuntime.create(context.applicationContext).also {
                runtime = it
            }
        }
    }
}
