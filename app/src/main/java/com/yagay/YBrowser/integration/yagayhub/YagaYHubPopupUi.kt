package com.yagay.YBrowser.integration.yagayhub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray

data class YagaYHubPopupTarget(
    val repoKey: String,
    val project: String,
    val url: String,
    val title: String,
    val addedAt: Long,
)

@Composable
fun YagaYHubCompactNavigation(
    current: YagaYHubPopupTarget?,
    targets: List<YagaYHubPopupTarget>,
    currentBindingProject: String?,
    currentPageUrl: String,
    currentPageTitle: String,
    onSelect: (YagaYHubPopupTarget) -> Unit,
    onRefresh: () -> Unit,
    onBind: () -> Unit,
    onUnbind: () -> Unit,
    onClose: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showUnbindConfirm by remember { mutableStateOf(false) }
    val activeUrl = currentPageUrl.ifBlank { current?.url.orEmpty() }
    val activeTitle = currentPageTitle.ifBlank { current?.title.orEmpty() }
    val activeAi = aiServiceName(activeUrl, activeTitle)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = (current?.project ?: "AI") + " · " + activeAi,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (current != null) {
                            Text(
                                text = current.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "切换 AI 绑定项目",
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    targets.forEach { target ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        target.project + " · " +
                                            aiServiceName(
                                                target.url,
                                                target.title,
                                            ),
                                        fontWeight = if (target == current) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                        maxLines = 1,
                                    )
                                    Text(
                                        target.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onSelect(target)
                            },
                        )
                    }
                }
            }

            TextButton(
                onClick = {
                    if (currentBindingProject != null) {
                        showUnbindConfirm = true
                    } else {
                        onBind()
                    }
                },
            ) {
                Text(
                    if (currentBindingProject != null) "已绑定" else "绑定",
                    color = if (currentBindingProject != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭",
                )
            }
        }
    }

    if (showUnbindConfirm && currentBindingProject != null) {
        AlertDialog(
            onDismissRequest = { showUnbindConfirm = false },
            title = { Text("取消当前绑定？") },
            text = {
                Text(
                    "当前页面已绑定到“$currentBindingProject”。取消后，可以在新的聊天窗口重新点击“绑定”。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnbindConfirm = false
                        onUnbind()
                    },
                ) {
                    Text("取消绑定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUnbindConfirm = false },
                ) {
                    Text("保留绑定")
                }
            },
        )
    }
}

fun parseYagaYHubPopupTargets(
    raw: String?,
): List<YagaYHubPopupTarget> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull()
        ?: return emptyList()

    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url")
            if (url.isBlank()) continue
            val repoKey = item.optString("repoKey")
            add(
                YagaYHubPopupTarget(
                    repoKey = repoKey,
                    project = item.optString("project")
                        .ifBlank { repoKey.substringAfterLast('/') },
                    url = url,
                    title = item.optString("title").ifBlank { "AI" },
                    addedAt = item.optLong("addedAt", 0L),
                ),
            )
        }
    }
}

fun sameYagaYHubPopupUrl(
    left: String,
    right: String,
): Boolean =
    left.substringBefore('#').trimEnd('/') ==
        right.substringBefore('#').trimEnd('/')

private fun aiServiceName(
    url: String,
    title: String,
): String {
    val value = (url + " " + title).lowercase()
    val host = runCatching {
        android.net.Uri.parse(url).host.orEmpty().lowercase()
    }.getOrDefault("")

    return when {
        host == "chatgpt.com" ||
            host.endsWith(".chatgpt.com") ||
            host == "chat.openai.com" ||
            "chatgpt" in value -> "ChatGPT"

        host == "gemini.google.com" ||
            host.endsWith(".gemini.google.com") ||
            "gemini" in value -> "Gemini"

        host == "claude.ai" ||
            host.endsWith(".claude.ai") ||
            "claude" in value -> "Claude"

        host == "chat.deepseek.com" ||
            host.endsWith(".deepseek.com") ||
            "deepseek" in value -> "DeepSeek"

        host == "grok.com" ||
            host.endsWith(".grok.com") ||
            host == "x.ai" ||
            host.endsWith(".x.ai") ||
            "grok" in value -> "Grok"

        host == "copilot.microsoft.com" ||
            host.endsWith(".copilot.microsoft.com") ||
            "copilot" in value -> "Copilot"

        host == "perplexity.ai" ||
            host.endsWith(".perplexity.ai") ||
            "perplexity" in value -> "Perplexity"

        host == "qwen.ai" ||
            host.endsWith(".qwen.ai") ||
            "qwen" in value ||
            "通义" in value -> "Qwen"

        host == "kimi.com" ||
            host.endsWith(".kimi.com") ||
            host == "kimi.moonshot.cn" ||
            host.endsWith(".kimi.moonshot.cn") ||
            "kimi" in value -> "Kimi"

        host == "doubao.com" ||
            host.endsWith(".doubao.com") ||
            "doubao" in value ||
            "豆包" in value -> "Doubao"

        host == "yuanbao.tencent.com" ||
            host.endsWith(".yuanbao.tencent.com") ||
            "yuanbao" in value ||
            "元宝" in value -> "Yuanbao"

        host == "meta.ai" ||
            host.endsWith(".meta.ai") ||
            "meta ai" in value -> "Meta AI"

        host == "poe.com" ||
            host.endsWith(".poe.com") ||
            "poe" in value -> "Poe"

        else -> "AI"
    }
}
