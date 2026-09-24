package com.yagay.YBrowser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityInfoSheet(
    url: String,
    info: BrowserSecurityInfo?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        when {
                            info?.secure == true && info.securityException ->
                                "安全连接（使用证书例外）"
                            info?.secure == true -> "安全连接"
                            url.startsWith("https://", ignoreCase = true) ->
                                "HTTPS 连接"
                            else -> "未加密连接"
                        }
                    )
                },
                supportingContent = {
                    Text(
                        info?.host
                            ?.takeIf { it.isNotBlank() }
                            ?: url
                    )
                },
            )
            HorizontalDivider()

            SecurityInfoRow("渲染内核", info?.engine?.label ?: "未知")
            SecurityInfoRow(
                "连接",
                if (info?.secure == true) "HTTPS / TLS" else "未确认安全",
            )
            if (info?.mixedActive == true || info?.mixedPassive == true) {
                SecurityInfoRow(
                    "混合内容",
                    buildString {
                        if (info.mixedActive) append("活动内容 ")
                        if (info.mixedPassive) append("被动内容")
                    }.trim(),
                )
            }
            info?.subject?.takeIf { it.isNotBlank() }?.let {
                SecurityInfoRow("证书主体", it)
            }
            info?.issuer?.takeIf { it.isNotBlank() }?.let {
                SecurityInfoRow("签发机构", it)
            }
            info?.validFrom?.let {
                SecurityInfoRow(
                    "有效期开始",
                    DateFormat.getDateTimeInstance().format(Date(it)),
                )
            }
            info?.validUntil?.let {
                SecurityInfoRow(
                    "有效期结束",
                    DateFormat.getDateTimeInstance().format(Date(it)),
                )
            }

            Text(
                "YBrowser 不允许从此面板绕过 SSL 证书错误；证书校验失败时仍由内核阻止连接。",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SecurityInfoRow(
    label: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
    )
}
