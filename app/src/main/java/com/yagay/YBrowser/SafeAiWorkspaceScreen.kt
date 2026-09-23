package com.yagay.YBrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yagay.ybrowser.ai.model.MessageRole
import com.yagay.ybrowser.ai.ui.WorkspaceViewModel

/**
 * Crash-resistant AI entry used by YagaYHub.
 *
 * This screen intentionally does not create Gecko/WebView during composition.
 * Provider runtime is requested only from explicit user actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SafeAiWorkspaceScreen(
    vm: WorkspaceViewModel,
    runtimeError: String?,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWeb: (String) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val messages = vm.messages

    LaunchedEffect(vm.activeWindowId, messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            vm.activeWindow.boundProject
                                .orEmpty()
                                .ifBlank { vm.activeWindow.title }
                                .ifBlank { "AI Work" },
                            maxLines = 1,
                        )
                        Text(
                            vm.activeProvider.name,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !vm.activeWindow.generating,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新聊天",
                        )
                    }
                    IconButton(
                        onClick = {
                            val url =
                                vm.activeWindow.boundUrl
                                    ?: vm.activeWindow.url
                                    ?: vm.activeProvider.homeUrl
                            onOpenWeb(url)
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Language,
                            contentDescription = "网页",
                        )
                    }
                    IconButton(
                        onClick = {
                            vm.newWindow()
                        },
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "新聊天",
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                vm.activeStatus
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                runtimeError
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        value = vm.activeDraft,
                        onValueChange = vm::updateDraft,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("输入消息")
                        },
                        maxLines = 6,
                        shape = RoundedCornerShape(22.dp),
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled =
                            !vm.activeWindow.generating &&
                                vm.activeDraft.isNotBlank(),
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "发送",
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = vm.tabWindows,
                    key = { it.id },
                ) { window ->
                    FilterChip(
                        selected = window.id == vm.activeWindowId,
                        onClick = {
                            vm.switchWindow(window.id)
                        },
                        label = {
                            Text(
                                window.boundProject
                                    .orEmpty()
                                    .ifBlank { window.title }
                                    .ifBlank { "聊天" },
                                maxLines = 1,
                            )
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AssistChip(
                    onClick = {
                        vm.requestBinding(vm.activeWindowId)
                    },
                    label = {
                        Text(
                            if (vm.activeWindow.boundRepo.isNullOrBlank()) {
                                "绑定项目"
                            } else {
                                "重新绑定"
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = null,
                        )
                    },
                )
            }

            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "本地聊天已就绪",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = messages,
                        key = { it.id },
                    ) { message ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp),
                            horizontalArrangement =
                                if (message.role == MessageRole.USER) {
                                    Arrangement.End
                                } else {
                                    Arrangement.Start
                                },
                        ) {
                            Surface(
                                modifier = Modifier.widthIn(max = 560.dp),
                                shape = RoundedCornerShape(18.dp),
                                tonalElevation =
                                    if (message.role == MessageRole.USER) {
                                        3.dp
                                    } else {
                                        0.dp
                                    },
                            ) {
                                Column(
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 10.dp,
                                    ),
                                ) {
                                    if (message.role == MessageRole.SYSTEM) {
                                        Text(
                                            "系统",
                                            style =
                                                MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    Text(message.text)
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.padding(bottom = 4.dp))
                    }
                }
            }
        }
    }
}


@Composable
internal fun SafeAiWorkspaceFailureScreen(
    message: String,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "AI Work 无法初始化",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
            )
            AssistChip(
                onClick = onClose,
                label = { Text("返回 YagaYHub") },
            )
        }
    }
}
