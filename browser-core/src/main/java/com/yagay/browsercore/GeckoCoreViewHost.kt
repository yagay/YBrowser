package com.yagay.browsercore

import android.content.Context
import android.content.MutableContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoView

/**
 * Owns the single visible GeckoView used by the AI workspace.
 *
 * Tabs own GeckoSession instances, not GeckoView instances. Switching tabs
 * releases the current session from this one view and attaches the target
 * session without closing or reloading either session.
 */
class GeckoCoreViewHost(context: Context) {
    private val appContext = context.applicationContext
    private val contextWrapper = MutableContextWrapper(appContext)
    private val view = GeckoView(contextWrapper)

    private var boundKey: String? = null
    private var boundSession: GeckoCoreSession? = null

    val androidView: View
        get() = view

    val currentKey: String?
        get() = boundKey

    @Synchronized
    fun attach(
        host: FrameLayout,
        hostContext: Context,
        key: String,
        session: GeckoCoreSession,
    ) {
        contextWrapper.baseContext = hostContext

        if (boundSession !== session) {
            boundSession?.setFocused(false)
            if (view.getSession() != null) {
                runCatching { view.releaseSession() }
            }
            session.attachTo(view)
            boundSession = session
            boundKey = key
        }

        val parent = view.parent as? ViewGroup
        if (
            parent !== host ||
            host.childCount != 1 ||
            host.getChildAt(0) !== view
        ) {
            parent?.removeView(view)
            host.removeAllViews()
            host.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        session.setActive(true)
        session.setFocused(true)
    }

    @Synchronized
    fun detachFromUi() {
        boundSession?.setFocused(false)
        if (view.getSession() != null) {
            runCatching { view.releaseSession() }
        }
        (view.parent as? ViewGroup)?.removeView(view)
        contextWrapper.baseContext = appContext
        boundKey = null
        boundSession = null
    }

    @Synchronized
    fun releaseIfBound(key: String) {
        if (boundKey != key) return
        detachFromUi()
    }
}
