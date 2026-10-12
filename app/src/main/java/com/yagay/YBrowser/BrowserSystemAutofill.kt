package com.yagay.YBrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.View
import android.view.autofill.AutofillManager

/**
 * Android's password provider works with the browser's virtual HTML children.
 * The app never reads the provider's saved credentials.
 */
object BrowserSystemAutofill {
    enum class ServiceState(val description: String) {
        UNSUPPORTED("Android 此环境不支持 Autofill"),
        NOT_CONFIGURED("系统自动填充服务未开启；请在系统设置选择密码管理器"),
        READY("系统 Autofill 已开启；是否出现账号建议由当前网页及密码管理器决定"),
    }

    enum class Result(val description: String) {
        SERVICE_UNAVAILABLE("系统未启用自动填充服务，请先选择密码管理器"),
        VIEW_NOT_READY("网页视图尚未就绪或 Autofill 已关闭"),
        NO_FOCUSED_FIELD("没有检测到网页输入框焦点，请先点选用户名或密码框再调用"),
        REQUEST_SENT("已向系统请求当前输入框的自动填充；是否显示建议由密码管理器决定"),
        REQUEST_FAILED("系统自动填充请求失败，请检查系统服务和网页输入框"),
    }

    fun state(context: Context): ServiceState {
        val manager = context.getSystemService(AutofillManager::class.java)
            ?: return ServiceState.UNSUPPORTED
        return runCatching {
            when {
                !manager.isAutofillSupported -> ServiceState.UNSUPPORTED
                !manager.isEnabled -> ServiceState.NOT_CONFIGURED
                else -> ServiceState.READY
            }
        }.getOrDefault(ServiceState.UNSUPPORTED)
    }

    fun openSettings(context: Context): Boolean {
        // There is no public cross-OEM Android intent guaranteed to open the
        // password provider selector. Try known Settings routes, then a
        // documented input settings page and finally standard Settings.
        val actions = listOf(
            "android.settings.AUTOFILL_SETTINGS",
            Settings.ACTION_INPUT_METHOD_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (action in actions) {
            if (runCatching {
                    context.startActivity(Intent(action).apply {
                        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.isSuccess
            ) return true
        }
        return false
    }

    fun request(
        view: View,
        virtualField: Pair<Int, Rect>? = null,
        requiresVirtualField: Boolean = false,
        onResult: (Result) -> Unit,
    ) {
        if (state(view.context) != ServiceState.READY) {
            onResult(Result.SERVICE_UNAVAILABLE)
            return
        }
        if (!view.isAttachedToWindow ||
            view.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_YES ||
            view.visibility != View.VISIBLE
        ) {
            onResult(Result.VIEW_NOT_READY)
            return
        }
        if (requiresVirtualField && virtualField == null) {
            onResult(Result.NO_FOCUSED_FIELD)
            return
        }

        // The action is chosen inside a modal sheet. Wait for the sheet to
        // leave so its window no longer obscures the browser's input field.
        val posted = view.postDelayed({
            val manager = view.context.getSystemService(AutofillManager::class.java)
            val status = if (
                manager == null || !manager.isEnabled ||
                !view.isAttachedToWindow ||
                view.visibility != View.VISIBLE ||
                view.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_YES
            ) {
                Result.VIEW_NOT_READY
            } else {
                runCatching {
                    if (virtualField == null) {
                        // WebView manages focused HTML virtual descendants
                        // internally. Its public API does not expose the ID.
                        manager.requestAutofill(view)
                    } else {
                        manager.requestAutofill(view, virtualField.first, virtualField.second)
                    }
                    Result.REQUEST_SENT
                }.getOrDefault(Result.REQUEST_FAILED)
            }
            onResult(status)
        }, 320L)
        if (!posted) onResult(Result.VIEW_NOT_READY)
    }
}
