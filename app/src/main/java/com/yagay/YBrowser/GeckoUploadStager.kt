package com.yagay.YBrowser

import android.content.Context
import android.net.Uri
import com.yagay.browsercore.GeckoCoreUploadStager

/**
 * Single Gecko upload staging implementation shared by the browser and AI
 * workspace. Keep the browser-specific diagnostics here, not the copying,
 * size limits, file-name sanitation, or cleanup algorithm.
 */
internal class GeckoUploadStager(context: Context) {
    private val appContext = context.applicationContext
    private val delegate = GeckoCoreUploadStager(appContext)

    fun stageAsync(uris: List<Uri>, complete: (Array<Uri>?) -> Unit) {
        delegate.stageAsync(uris) { staged ->
            // Diagnostics contain counts only; never log upload URIs or paths.
            BrowserNavigationLog.log(
                appContext,
                if (staged == null) "GECKO_UPLOAD_STAGE_FAILED" else "GECKO_UPLOAD_STAGE",
                "selected=" + uris.size + " staged=" + (staged?.size ?: 0),
            )
            complete(staged)
        }
    }

    fun releaseAll() = delegate.releaseAll()
}
