package com.yagay.browsercore

import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoView

/**
 * Owns the single visible GeckoView used by the AI workspace.
 *
 * The view is created lazily with the real host/activity context. Creating a
 * GeckoView eagerly from applicationContext can crash on some Android/OEM
 * builds when the AI workspace is launched directly, before normal browser UI
 * setup has attached a window/theme context.
 */
class GeckoCoreViewHost(context: Context) {
    private val appContext =
        context.applicationContext

    private var contextWrapper:
        MutableContextWrapper? = null
    private var view: GeckoView? = null

    private var boundKey: String? = null
    private var boundSession: GeckoCoreSession? = null

    val currentKey: String?
        get() = boundKey

    @Synchronized
    fun attach(
        host: FrameLayout,
        hostContext: Context,
        key: String,
        session: GeckoCoreSession,
    ) {
        val geckoView =
            ensureView(hostContext)
        val wrapper =
            contextWrapper
                ?: return
        wrapper.baseContext = hostContext

        val sessionChanged =
            boundSession !== session
        if (sessionChanged) {
            if (boundSession != null) {
                boundSession?.setFocused(false)
                runCatching {
                    geckoView.releaseSession()
                }
            }
            session.attachTo(geckoView)
            boundSession = session
            boundKey = key
        }

        val parent =
            geckoView.parent as? ViewGroup
        if (
            parent !== host ||
            host.childCount != 1 ||
            host.getChildAt(0) !==
                geckoView
        ) {
            parent?.removeView(geckoView)
            host.removeAllViews()
            host.addView(
                geckoView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        if (sessionChanged) {
            session.setActive(true)
            session.setFocused(true)
        }
    }

    @Synchronized
    fun detachFromUi() {
        val geckoView = view
        if (
            boundSession != null &&
            geckoView != null
        ) {
            boundSession?.setFocused(false)
            runCatching {
                geckoView.releaseSession()
            }
        }
        (geckoView?.parent as? ViewGroup)
            ?.removeView(geckoView)
        contextWrapper?.baseContext =
            appContext
        boundKey = null
        boundSession = null
    }

    @Synchronized
    fun releaseIfBound(key: String) {
        if (boundKey != key) return
        detachFromUi()
    }

    @Synchronized
    fun destroy() {
        detachFromUi()
        view = null
        contextWrapper = null
    }

    private fun ensureView(
        hostContext: Context,
    ): GeckoView {
        view?.let { return it }

        val wrapper =
            MutableContextWrapper(
                hostContext
            )
        val created =
            GeckoView(wrapper)

        contextWrapper = wrapper
        view = created
        return created
    }
}
