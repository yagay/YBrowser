package com.yagay.YBrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

data class BrowserExtensionInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val allowedInPrivateBrowsing: Boolean,
    val optionsPageUrl: String?,
    val sourceUrl: String?,
)

data class BrowserExtensionOperationResult(
    val success: Boolean,
    val message: String,
)

data class BrowserExtensionPermissionRequest(
    val extensionName: String,
    val kind: String,
    val permissions: List<String>,
    val origins: List<String>,
    val dataCollectionPermissions: List<String>,
    val decide: (Boolean) -> Unit,
)

object GeckoExtensionManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var prepared = false
    private var permissionUi: ((BrowserExtensionPermissionRequest) -> Unit)? = null
    private var pendingPermission: BrowserExtensionPermissionRequest? = null

    /**
     * Required permissions are NEVER approved without explicit UI consent.
     * Dismissing the UI also completes pending GeckoResults with denial.
     */
    fun bindPermissionUi(handler: ((BrowserExtensionPermissionRequest) -> Unit)?) {
        mainHandler.post {
            pendingPermission?.decide?.invoke(false)
            pendingPermission = null
            permissionUi = handler
        }
    }

    private fun requestPermission(
        extension: WebExtension,
        kind: String,
        permissions: Array<out String>,
        origins: Array<out String>,
        dataCollectionPermissions: Array<out String>,
        callback: (Boolean) -> Unit,
    ) {
        val once = AtomicBoolean(false)
        val request = BrowserExtensionPermissionRequest(
            extensionName = extension.metaData.name ?: extension.id,
            kind = kind,
            permissions = permissions.toList(),
            origins = origins.toList(),
            dataCollectionPermissions = dataCollectionPermissions.toList(),
            decide = { allow ->
                if (once.compareAndSet(false, true)) {
                    mainHandler.post { callback(allow) }
                }
            },
        )
        mainHandler.post {
            val ui = permissionUi
            if (ui == null) {
                request.decide(false)
            } else {
                pendingPermission?.decide?.invoke(false)
                pendingPermission = request
                ui(request)
            }
        }
    }

    private fun controller(context: Context): WebExtensionController {
        val controller = GeckoRuntimeHolder.get(context).webExtensionController
        if (!prepared) {
            synchronized(this) {
                if (!prepared) {
                    controller.promptDelegate = object : WebExtensionController.PromptDelegate {
                        override fun onInstallPromptRequest(
                            extension: WebExtension,
                            permissions: Array<out String>,
                            origins: Array<out String>,
                            dataCollectionPermissions: Array<out String>,
                        ): GeckoResult<WebExtension.PermissionPromptResponse> {
                            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                            requestPermission(
                                extension, "安装权限", permissions, origins,
                                dataCollectionPermissions,
                            ) { accepted ->
                                result.complete(
                                    WebExtension.PermissionPromptResponse(accepted, false, false),
                                )
                            }
                            return result
                        }

                        override fun onOptionalPrompt(
                            extension: WebExtension,
                            permissions: Array<out String>,
                            origins: Array<out String>,
                            dataCollectionPermissions: Array<out String>,
                        ): GeckoResult<AllowOrDeny> {
                            val result = GeckoResult<AllowOrDeny>()
                            requestPermission(
                                extension, "额外权限", permissions, origins,
                                dataCollectionPermissions,
                            ) { accepted ->
                                result.complete(if (accepted) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                            }
                            return result
                        }

                        override fun onUpdatePrompt(
                            extension: WebExtension,
                            newPermissions: Array<out String>,
                            newOrigins: Array<out String>,
                            newDataCollectionPermissions: Array<out String>,
                        ): GeckoResult<AllowOrDeny> {
                            val result = GeckoResult<AllowOrDeny>()
                            requestPermission(
                                extension, "更新权限", newPermissions, newOrigins,
                                newDataCollectionPermissions,
                            ) { accepted ->
                                result.complete(if (accepted) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                            }
                            return result
                        }
                    }
                    prepared = true
                }
            }
        }
        return controller
    }

    fun list(
        context: Context,
        onResult: (List<BrowserExtensionInfo>) -> Unit,
    ) {
        controller(context)
            .list()
            .withHandler(mainHandler)
            .accept(
                { extensions ->
                    onResult(
                        extensions.orEmpty()
                            .map(::toInfo)
                            .sortedBy { it.name.lowercase() },
                    )
                },
                { onResult(emptyList()) },
            )
    }

    fun install(
        context: Context,
        xpiUrl: String,
        onResult: (BrowserExtensionOperationResult) -> Unit,
    ) {
        val url = xpiUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true) ||
            !url.substringBefore('?').endsWith(".xpi", ignoreCase = true)
        ) {
            onResult(
                BrowserExtensionOperationResult(
                    false,
                    "请输入 HTTPS 的 .xpi 扩展地址",
                ),
            )
            return
        }

        controller(context)
            .install(url, WebExtensionController.INSTALLATION_METHOD_MANAGER)
            .withHandler(mainHandler)
            .accept(
                { extension ->
                    if (extension == null) {
                        onResult(
                            BrowserExtensionOperationResult(
                                false,
                                "安装失败：GeckoView 未返回扩展",
                            ),
                        )
                    } else {
                        onResult(
                            BrowserExtensionOperationResult(
                                true,
                                "已安装 " + (extension.metaData.name ?: extension.id),
                            ),
                        )
                    }
                },
                { error ->
                    onResult(
                        BrowserExtensionOperationResult(
                            false,
                            "安装失败：" + (error?.message ?: error?.javaClass?.simpleName ?: "未知错误"),
                        ),
                    )
                },
            )
    }

    fun setEnabled(
        context: Context,
        extensionId: String,
        enabled: Boolean,
        onResult: (BrowserExtensionOperationResult) -> Unit,
    ) {
        withExtension(context, extensionId, onResult) { controller, extension ->
            val action = if (enabled) {
                controller.enable(extension, WebExtensionController.EnableSource.USER)
            } else {
                controller.disable(extension, WebExtensionController.EnableSource.USER)
            }
            action.withHandler(mainHandler).accept(
                {
                    onResult(
                        BrowserExtensionOperationResult(
                            true,
                            if (enabled) "扩展已启用" else "扩展已停用",
                        ),
                    )
                },
                { error ->
                    onResult(
                        BrowserExtensionOperationResult(
                            false,
                            error?.message ?: "操作失败",
                        ),
                    )
                },
            )
        }
    }

    fun setAllowedInPrivateBrowsing(
        context: Context,
        extensionId: String,
        allowed: Boolean,
        onResult: (BrowserExtensionOperationResult) -> Unit,
    ) {
        withExtension(context, extensionId, onResult) { controller, extension ->
            controller.setAllowedInPrivateBrowsing(extension, allowed)
                .withHandler(mainHandler)
                .accept(
                    {
                        onResult(
                            BrowserExtensionOperationResult(
                                true,
                                if (allowed) "已允许隐私模式" else "已禁止隐私模式",
                            ),
                        )
                    },
                    { error ->
                        onResult(
                            BrowserExtensionOperationResult(
                                false,
                                error?.message ?: "操作失败",
                            ),
                        )
                    },
                )
        }
    }

    fun update(
        context: Context,
        extensionId: String,
        onResult: (BrowserExtensionOperationResult) -> Unit,
    ) {
        withExtension(context, extensionId, onResult) { controller, extension ->
            controller.update(extension)
                .withHandler(mainHandler)
                .accept(
                    { updated ->
                        onResult(
                            BrowserExtensionOperationResult(
                                true,
                                if (updated == null) "已经是最新版本" else "扩展已更新",
                            ),
                        )
                    },
                    { error ->
                        onResult(
                            BrowserExtensionOperationResult(
                                false,
                                error?.message ?: "更新失败",
                            ),
                        )
                    },
                )
        }
    }

    fun uninstall(
        context: Context,
        extensionId: String,
        onResult: (BrowserExtensionOperationResult) -> Unit,
    ) {
        withExtension(context, extensionId, onResult) { controller, extension ->
            controller.uninstall(extension)
                .withHandler(mainHandler)
                .accept(
                    {
                        onResult(
                            BrowserExtensionOperationResult(
                                true,
                                "扩展已卸载",
                            ),
                        )
                    },
                    { error ->
                        onResult(
                            BrowserExtensionOperationResult(
                                false,
                                error?.message ?: "卸载失败",
                            ),
                        )
                    },
                )
        }
    }

    private fun withExtension(
        context: Context,
        extensionId: String,
        onResult: (BrowserExtensionOperationResult) -> Unit,
        action: (WebExtensionController, WebExtension) -> Unit,
    ) {
        val controller = controller(context)
        controller.list()
            .withHandler(mainHandler)
            .accept(
                { extensions ->
                    val extension = extensions.orEmpty().firstOrNull { it.id == extensionId }
                    if (extension == null) {
                        onResult(BrowserExtensionOperationResult(false, "没有找到扩展"))
                    } else {
                        action(controller, extension)
                    }
                },
                { error ->
                    onResult(
                        BrowserExtensionOperationResult(
                            false,
                            error?.message ?: "无法读取扩展列表",
                        ),
                    )
                },
            )
    }

    private fun toInfo(extension: WebExtension): BrowserExtensionInfo {
        val meta = extension.metaData
        return BrowserExtensionInfo(
            id = extension.id,
            name = meta.name ?: extension.id,
            version = meta.version,
            description = meta.description.orEmpty(),
            enabled = meta.enabled,
            allowedInPrivateBrowsing = meta.allowedInPrivateBrowsing,
            optionsPageUrl = meta.optionsPageUrl,
            sourceUrl = meta.downloadUrl,
        )
    }
}
