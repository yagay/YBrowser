package com.yagay.ybrowser.ai.ui
import coil3.compose.AsyncImage
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import android.net.Uri
import android.content.Intent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatMessage
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.MessageRole
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WindowTabStrip(
    windows: List<ChatWindow>,
    activeWindowId: String,
    focusRevision: Int,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(
        activeWindowId,
        windows.map { it.id },
        focusRevision,
    ) {
        val index = windows.indexOfFirst { it.id == activeWindowId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(windows, key = { it.id }) { window ->
            val selected = window.id == activeWindowId
            val label = window.boundProject.orEmpty()
                .ifBlank { window.title }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Row(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onSelect(window.id) },
                            onLongClick = { onLongPress(window.id) },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append(label)
                            when {
                                window.generating -> append(" ⟳")
                                window.unread -> append(" ●")
                            }
                        },
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }
            }
        }
    }
}



@Composable
internal fun NativeChatPane(
    messages: List<ChatMessage>,
    status: String?,
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    attachments: List<AttachmentMeta>,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    visible: Boolean,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followBottom by remember { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }

    LaunchedEffect(
        atBottom,
        listState.isScrollInProgress,
    ) {
        when {
            atBottom -> followBottom = true
            listState.isScrollInProgress ->
                followBottom = false
        }
    }

    LaunchedEffect(
        messages.size,
        generating,
        visible,
    ) {
        if (
            visible &&
            followBottom &&
            messages.isNotEmpty()
        ) {
            listState.animateScrollToItem(
                messages.lastIndex
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .alpha(if (visible) 1f else 0f)
            .zIndex(if (visible) 1f else -1f)
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 20.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement =
                    Arrangement.spacedBy(20.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = 72.dp,
                                    start = 24.dp,
                                    end = 24.dp,
                                ),
                            horizontalAlignment =
                                Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color =
                                    MaterialTheme.colorScheme
                                        .primaryContainer,
                            ) {
                                Box(
                                    Modifier.size(64.dp),
                                    contentAlignment =
                                        Alignment.Center,
                                ) {
                                    Text(
                                        "AI",
                                        style =
                                            MaterialTheme.typography
                                                .headlineMedium,
                                        fontWeight =
                                            FontWeight.SemiBold,
                                    )
                                }
                            }

                            Spacer(Modifier.height(18.dp))

                            Text(
                                "开始聊天",
                                style =
                                    MaterialTheme.typography
                                        .headlineSmall,
                            )

                            Text(
                                "聊天内容由 YBrowser 同步，项目切换不会重新加载网页。",
                                style =
                                    MaterialTheme.typography
                                        .bodyMedium,
                                color =
                                    MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                modifier = Modifier
                                    .widthIn(max = 560.dp)
                                    .padding(top = 8.dp),
                            )
                        }
                    }
                }

                items(
                    messages,
                    key = { it.id },
                ) { message ->
                    MessageBubble(message)
                }

                if (status != null) {
                    item {
                        Box(
                            modifier =
                                Modifier.fillMaxWidth(),
                            contentAlignment =
                                Alignment.Center,
                        ) {
                            Text(
                                status,
                                style =
                                    MaterialTheme.typography
                                        .bodySmall,
                                color =
                                    MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                modifier = Modifier
                                    .widthIn(max = 760.dp)
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = 18.dp,
                                    ),
                            )
                        }
                    }
                }
            }

            if (
                !followBottom &&
                listState.canScrollForward
            ) {
                FilledIconButton(
                    onClick = {
                        followBottom = true
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(
                                    messages.lastIndex
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .size(42.dp),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription =
                            "回到最新消息",
                    )
                }
            }
        }

        ChatComposer(
            draft = draft,
            onDraftChange = onDraftChange,
            generating = generating,
            attachments = attachments,
            onAttach = onAttach,
            onSend = {
                followBottom = true
                onSend()
            },
            onStop = onStop,
        )
    }
}

@Composable
internal fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    attachments: List<AttachmentMeta>,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 10.dp,
                        vertical = 8.dp,
                    )
            ) {
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(
                                rememberScrollState()
                            ),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                    ) {
                        attachments.forEach { attachment ->
                            AttachmentCard(
                                attachment = attachment,
                                compact = true,
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                }

                Row(
                    verticalAlignment =
                        Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = onAttach,
                        enabled = !generating,
                    ) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            "添加附件",
                        )
                    }

                    TextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("询问任何问题")
                        },
                        minLines = 1,
                        maxLines = 7,
                        shape = RoundedCornerShape(24.dp),
                        colors =
                            TextFieldDefaults.colors(
                                focusedIndicatorColor =
                                    androidx.compose.ui
                                        .graphics.Color
                                        .Transparent,
                                unfocusedIndicatorColor =
                                    androidx.compose.ui
                                        .graphics.Color
                                        .Transparent,
                                disabledIndicatorColor =
                                    androidx.compose.ui
                                        .graphics.Color
                                        .Transparent,
                            ),
                    )

                    Spacer(Modifier.size(6.dp))

                    FilledIconButton(
                        onClick =
                            if (generating) {
                                onStop
                            } else {
                                onSend
                            },
                        enabled =
                            generating ||
                                draft.isNotBlank() ||
                                attachments.isNotEmpty(),
                    ) {
                        Icon(
                            if (generating) {
                                Icons.Default.Stop
                            } else {
                                Icons.Default.Send
                            },
                            if (generating) {
                                "停止"
                            } else {
                                "发送"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MessageBubble(
    message: ChatMessage,
) {
    val mine =
        message.role == MessageRole.USER
    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalAlignment =
                if (mine) {
                    Alignment.End
                } else {
                    Alignment.Start
                },
        ) {
            if (mine) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color =
                        MaterialTheme.colorScheme
                            .surfaceContainerHigh,
                    modifier =
                        Modifier.fillMaxWidth(0.86f),
                ) {
                    SelectionContainer {
                        ChatMarkdownContent(
                            text = message.text,
                            compact = true,
                            modifier =
                                Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 11.dp,
                                ),
                        )
                    }
                }
            } else {
                SelectionContainer {
                    ChatMarkdownContent(
                        text = message.text,
                        compact = false,
                        modifier =
                            Modifier.fillMaxWidth(),
                    )
                }
            }

            if (message.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(
                            rememberScrollState()
                        ),
                    horizontalArrangement =
                        if (mine) {
                            Arrangement.End
                        } else {
                            Arrangement.Start
                        },
                ) {
                    message.attachments.forEach {
                        attachment ->
                        AttachmentCard(
                            attachment = attachment,
                            modifier =
                                Modifier.padding(
                                    end = 8.dp
                                ),
                        )
                    }
                }
            }

            if (!mine) {
                IconButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "AI reply",
                                message.text,
                            )
                        )
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.size(17.dp),
                        tint =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal enum class ChatBlockType {
    PARAGRAPH,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BULLET,
    NUMBERED,
    QUOTE,
    CODE,
    IMAGE,
}

internal data class ChatTextBlock(
    val type: ChatBlockType,
    val text: String,
    val marker: String = "",
)

internal fun parseChatTextBlocks(
    raw: String,
): List<ChatTextBlock> {
    val codeMark = 96.toChar()
    val fence =
        codeMark.toString().repeat(3)
    val lines =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
    val blocks =
        mutableListOf<ChatTextBlock>()
    var index = 0

    fun isSpecial(
        line: String,
    ): Boolean {
        val value = line.trimStart()
        return value.startsWith(fence) ||
            value.startsWith("# ") ||
            value.startsWith("## ") ||
            value.startsWith("### ") ||
            value.startsWith("> ") ||
            value.startsWith("- ") ||
            value.startsWith("* ") ||
            parseMarkdownImage(value) != null ||
            isLikelyImageUrl(value) ||
            Regex("""^\d+\.\s+.+""")
                .matches(value)
    }

    while (index < lines.size) {
        val trimmed =
            lines[index].trim()

        if (trimmed.isBlank()) {
            index += 1
            continue
        }

        if (trimmed.startsWith(fence)) {
            val language =
                trimmed.removePrefix(fence)
                    .trim()
            index += 1
            val code =
                mutableListOf<String>()
            while (
                index < lines.size &&
                !lines[index]
                    .trim()
                    .startsWith(fence)
            ) {
                code += lines[index]
                index += 1
            }
            if (index < lines.size) {
                index += 1
            }
            blocks += ChatTextBlock(
                type = ChatBlockType.CODE,
                text = code.joinToString("\n"),
                marker = language,
            )
            continue
        }

        when {
            parseMarkdownImage(trimmed) != null -> {
                val image =
                    parseMarkdownImage(trimmed)!!
                blocks += ChatTextBlock(
                    ChatBlockType.IMAGE,
                    image.first,
                    marker = image.second,
                )
                index += 1
            }

            isLikelyImageUrl(trimmed) -> {
                blocks += ChatTextBlock(
                    ChatBlockType.IMAGE,
                    "",
                    marker = trimmed,
                )
                index += 1
            }

            trimmed.startsWith("### ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.HEADING_3,
                    trimmed.removePrefix("### "),
                )
                index += 1
            }

            trimmed.startsWith("## ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.HEADING_2,
                    trimmed.removePrefix("## "),
                )
                index += 1
            }

            trimmed.startsWith("# ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.HEADING_1,
                    trimmed.removePrefix("# "),
                )
                index += 1
            }

            trimmed.startsWith("> ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.QUOTE,
                    trimmed.removePrefix("> "),
                )
                index += 1
            }

            trimmed.startsWith("- ") ||
                trimmed.startsWith("* ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.BULLET,
                    trimmed.drop(2),
                    marker = "•",
                )
                index += 1
            }

            Regex("""^\d+\.\s+.+""")
                .matches(trimmed) -> {
                blocks += ChatTextBlock(
                    ChatBlockType.NUMBERED,
                    trimmed
                        .substringAfter(".")
                        .trim(),
                    marker =
                        trimmed
                            .substringBefore(".") +
                            ".",
                )
                index += 1
            }

            else -> {
                val paragraph =
                    mutableListOf<String>()
                while (
                    index < lines.size &&
                    lines[index].isNotBlank() &&
                    !isSpecial(lines[index])
                ) {
                    paragraph +=
                        lines[index].trim()
                    index += 1
                }
                if (paragraph.isNotEmpty()) {
                    blocks += ChatTextBlock(
                        ChatBlockType.PARAGRAPH,
                        paragraph.joinToString(
                            "\n"
                        ),
                    )
                } else {
                    index += 1
                }
            }
        }
    }

    return blocks
}

@Composable
internal fun ChatMarkdownContent(
    text: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) {
        parseChatTextBlocks(text)
    }

    Column(
        modifier = modifier,
        verticalArrangement =
            Arrangement.spacedBy(
                if (compact) 6.dp else 10.dp
            ),
    ) {
        blocks.forEach { block ->
            when (block.type) {
                ChatBlockType.HEADING_1,
                ChatBlockType.HEADING_2,
                ChatBlockType.HEADING_3 -> {
                    val style =
                        when (block.type) {
                            ChatBlockType.HEADING_1 ->
                                MaterialTheme
                                    .typography
                                    .headlineSmall
                            ChatBlockType.HEADING_2 ->
                                MaterialTheme
                                    .typography
                                    .titleLarge
                            else ->
                                MaterialTheme
                                    .typography
                                    .titleMedium
                        }

                    ChatInlineMarkdown(
                        text = block.text,
                        style = style,
                        fontWeight =
                            FontWeight.SemiBold,
                    )
                }

                ChatBlockType.CODE -> {
                    Surface(
                        shape =
                            RoundedCornerShape(
                                12.dp
                            ),
                        color =
                            MaterialTheme.colorScheme
                                .surfaceContainerHighest,
                        modifier =
                            Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier =
                                Modifier.padding(
                                    horizontal = 13.dp,
                                    vertical = 11.dp,
                                ),
                        ) {
                            if (
                                block.marker
                                    .isNotBlank()
                            ) {
                                Text(
                                    block.marker,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant,
                                    modifier =
                                        Modifier.padding(
                                            bottom = 7.dp,
                                        ),
                                )
                            }

                            Text(
                                block.text,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyMedium
                                        .copy(
                                            fontFamily =
                                                FontFamily
                                                    .Monospace,
                                            lineHeight =
                                                20.sp,
                                        ),
                                modifier =
                                    Modifier
                                        .horizontalScroll(
                                            rememberScrollState()
                                        ),
                            )
                        }
                    }
                }

                ChatBlockType.BULLET,
                ChatBlockType.NUMBERED -> {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.Top,
                    ) {
                        Text(
                            block.marker,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(
                                        lineHeight =
                                            26.sp
                                    ),
                            modifier =
                                Modifier.width(28.dp),
                        )
                        ChatInlineMarkdown(
                            text = block.text,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(
                                        lineHeight =
                                            26.sp
                                    ),
                            modifier =
                                Modifier.weight(1f),
                        )
                    }
                }

                ChatBlockType.QUOTE -> {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            color =
                                MaterialTheme.colorScheme
                                    .outlineVariant,
                            modifier = Modifier
                                .width(3.dp)
                                .height(26.dp),
                        ) {}
                        ChatInlineMarkdown(
                            text = block.text,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(
                                        lineHeight =
                                            26.sp
                                    ),
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        )
                    }
                }

                ChatBlockType.IMAGE -> {
                    ChatImageBlock(
                        url = block.marker,
                        alt = block.text,
                    )
                }

                ChatBlockType.PARAGRAPH -> {
                    ChatInlineMarkdown(
                        text = block.text,
                        style =
                            MaterialTheme.typography
                                .bodyLarge
                                .copy(
                                    lineHeight =
                                        if (compact) {
                                            25.sp
                                        } else {
                                            26.sp
                                        },
                                ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChatInlineMarkdown(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color:
        androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.onSurface,
    fontWeight: FontWeight? = null,
) {
    val context = LocalContext.current
    val background =
        MaterialTheme.colorScheme
            .surfaceContainerHighest
    val linkColor =
        MaterialTheme.colorScheme.primary
    val annotated =
        remember(
            text,
            background,
            linkColor,
        ) {
            buildInlineMarkdown(
                text = text,
                codeBackground = background,
                linkColor = linkColor,
            )
        }

    ClickableText(
        text = annotated,
        style =
            style.copy(
                color = color,
                fontWeight = fontWeight,
            ),
        modifier = modifier,
        onClick = { offset ->
            annotated
                .getStringAnnotations(
                    tag = URL_TAG,
                    start = offset,
                    end = offset,
                )
                .firstOrNull()
                ?.item
                ?.let { uri ->
                    openExternalUri(
                        context,
                        uri,
                    )
                }
        },
    )
}

internal fun buildInlineMarkdown(
    text: String,
    codeBackground:
        androidx.compose.ui.graphics.Color,
    linkColor:
        androidx.compose.ui.graphics.Color =
        Color.Blue,
): AnnotatedString =
    buildAnnotatedString {
        val codeMark = 96.toChar()
        var cursor = 0

        fun appendLink(
            label: String,
            url: String,
        ) {
            val start = length
            withStyle(
                SpanStyle(
                    color = linkColor,
                    textDecoration =
                        TextDecoration.Underline,
                )
            ) {
                append(label)
            }
            addStringAnnotation(
                tag = URL_TAG,
                annotation = url,
                start = start,
                end = length,
            )
        }

        while (cursor < text.length) {
            when {
                text.startsWith(
                    "**",
                    cursor,
                ) -> {
                    val end =
                        text.indexOf(
                            "**",
                            cursor + 2,
                        )
                    if (end > cursor + 2) {
                        withStyle(
                            SpanStyle(
                                fontWeight =
                                    FontWeight.SemiBold,
                            )
                        ) {
                            append(
                                text.substring(
                                    cursor + 2,
                                    end,
                                )
                            )
                        }
                        cursor = end + 2
                    } else {
                        append(text[cursor])
                        cursor += 1
                    }
                }

                text[cursor] == codeMark -> {
                    val end =
                        text.indexOf(
                            codeMark,
                            cursor + 1,
                        )
                    if (end > cursor + 1) {
                        withStyle(
                            SpanStyle(
                                fontFamily =
                                    FontFamily.Monospace,
                                background =
                                    codeBackground,
                            )
                        ) {
                            append(
                                text.substring(
                                    cursor + 1,
                                    end,
                                )
                            )
                        }
                        cursor = end + 1
                    } else {
                        append(text[cursor])
                        cursor += 1
                    }
                }

                text[cursor] == '[' -> {
                    val closeLabel =
                        text.indexOf(
                            ']',
                            cursor + 1,
                        )
                    val openUrl =
                        if (
                            closeLabel >= 0 &&
                            closeLabel + 1 <
                                text.length &&
                            text[closeLabel + 1] == '('
                        ) {
                            closeLabel + 1
                        } else {
                            -1
                        }
                    val closeUrl =
                        if (openUrl >= 0) {
                            text.indexOf(
                                ')',
                                openUrl + 1,
                            )
                        } else {
                            -1
                        }

                    if (
                        closeLabel > cursor + 1 &&
                        closeUrl > openUrl + 1
                    ) {
                        val label =
                            text.substring(
                                cursor + 1,
                                closeLabel,
                            )
                        val url =
                            text.substring(
                                openUrl + 1,
                                closeUrl,
                            ).trim()
                        if (isOpenableUri(url)) {
                            appendLink(
                                label,
                                url,
                            )
                            cursor = closeUrl + 1
                        } else {
                            append(text[cursor])
                            cursor += 1
                        }
                    } else {
                        append(text[cursor])
                        cursor += 1
                    }
                }

                startsWithUrl(
                    text,
                    cursor,
                ) -> {
                    val end =
                        findUrlEnd(
                            text,
                            cursor,
                        )
                    val raw =
                        text.substring(
                            cursor,
                            end,
                        )
                    val trimmed =
                        raw.trimEnd(
                            '.',
                            ',',
                            ';',
                            ':',
                            '!',
                            '?',
                            ')',
                            ']',
                            '}',
                        )
                    appendLink(
                        trimmed,
                        trimmed,
                    )
                    if (
                        trimmed.length <
                        raw.length
                    ) {
                        append(
                            raw.substring(
                                trimmed.length
                            )
                        )
                    }
                    cursor = end
                }

                else -> {
                    val next =
                        nextInlineSpecial(
                            text,
                            cursor,
                            codeMark,
                        )
                    append(
                        text.substring(
                            cursor,
                            next,
                        )
                    )
                    cursor = next
                }
            }
        }
    }

private const val URL_TAG = "url"

private fun parseMarkdownImage(
    value: String,
): Pair<String, String>? {
    val match =
        Regex(
            """^!\[([^\]]*)]\((https?://[^\s)]+|content://[^\s)]+)\)$"""
        ).matchEntire(value.trim())
            ?: return null
    return match.groupValues[1] to
        match.groupValues[2]
}

private fun isOpenableUri(
    value: String,
): Boolean =
    runCatching {
        val scheme =
            Uri.parse(value).scheme
                ?.lowercase()
        scheme in setOf(
            "http",
            "https",
            "content",
            "file",
            "mailto",
            "tel",
        )
    }.getOrDefault(false)

private fun startsWithUrl(
    text: String,
    index: Int,
): Boolean =
    text.regionMatches(
        index,
        "https://",
        0,
        8,
        ignoreCase = true,
    ) ||
        text.regionMatches(
            index,
            "http://",
            0,
            7,
            ignoreCase = true,
        )

private fun findUrlEnd(
    text: String,
    start: Int,
): Int {
    var index = start
    while (
        index < text.length &&
        !text[index].isWhitespace()
    ) {
        index++
    }
    return index
}

private fun nextInlineSpecial(
    text: String,
    start: Int,
    codeMark: Char,
): Int {
    val candidates =
        listOf(
            text.indexOf("**", start),
            text.indexOf(codeMark, start),
            text.indexOf('[', start),
            text.indexOf("https://", start),
            text.indexOf("http://", start),
        ).filter {
            it >= 0
        }
    return candidates.minOrNull()
        ?: text.length
}

private fun isLikelyImageUrl(
    value: String,
): Boolean {
    if (!isOpenableUri(value)) return false
    val clean =
        value.substringBefore('#')
            .substringBefore('?')
            .lowercase()
    return clean.endsWith(".png") ||
        clean.endsWith(".jpg") ||
        clean.endsWith(".jpeg") ||
        clean.endsWith(".gif") ||
        clean.endsWith(".webp") ||
        clean.endsWith(".avif") ||
        clean.endsWith(".svg")
}

private fun isImageAttachment(
    attachment: AttachmentMeta,
): Boolean =
    attachment.mimeType
        .startsWith(
            "image/",
            ignoreCase = true,
        ) ||
        attachment.uri
            ?.let(::isLikelyImageUrl)
            == true

private fun openExternalUri(
    context: Context,
    value: String,
    mimeType: String? = null,
) {
    val uri =
        runCatching {
            Uri.parse(value)
        }.getOrNull()
            ?: return
    if (
        uri.scheme?.lowercase() !in
        setOf(
            "http",
            "https",
            "content",
            "file",
            "mailto",
            "tel",
        )
    ) {
        return
    }

    val intent =
        Intent(Intent.ACTION_VIEW).apply {
            if (
                !mimeType.isNullOrBlank() &&
                uri.scheme in
                    setOf(
                        "content",
                        "file",
                    )
            ) {
                setDataAndType(
                    uri,
                    mimeType,
                )
            } else {
                data = uri
            }
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    runCatching {
        context.startActivity(intent)
    }
}

@Composable
private fun ChatImageBlock(
    url: String,
    alt: String,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    openExternalUri(
                        context,
                        url,
                    )
                },
    ) {
        AsyncImage(
            model = url,
            contentDescription =
                alt.ifBlank {
                    "图片"
                },
            contentScale =
                ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = 120.dp,
                        max = 460.dp,
                    ),
        )
    }
}

@Composable
private fun AttachmentCard(
    attachment: AttachmentMeta,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val uri = attachment.uri
    val clickable =
        !uri.isNullOrBlank() &&
            isOpenableUri(uri)
    val cardModifier =
        modifier.then(
            if (clickable) {
                Modifier.clickable {
                    openExternalUri(
                        context,
                        uri!!,
                        attachment.mimeType,
                    )
                }
            } else {
                Modifier
            }
        )

    Surface(
        shape =
            RoundedCornerShape(
                if (compact) 12.dp
                else 10.dp
            ),
        color =
            MaterialTheme.colorScheme
                .surfaceContainer,
        modifier = cardModifier,
    ) {
        if (
            isImageAttachment(
                attachment
            ) &&
            !uri.isNullOrBlank()
        ) {
            Column {
                AsyncImage(
                    model = uri,
                    contentDescription =
                        attachment.name,
                    contentScale =
                        ContentScale.Crop,
                    modifier =
                        Modifier
                            .widthIn(
                                min = 120.dp,
                                max = 240.dp,
                            )
                            .heightIn(
                                min = 96.dp,
                                max = 220.dp,
                            ),
                )
                Text(
                    attachment.name,
                    style =
                        MaterialTheme.typography
                            .labelMedium,
                    maxLines = 1,
                    modifier =
                        Modifier.padding(
                            horizontal = 10.dp,
                            vertical = 7.dp,
                        ),
                )
            }
        } else {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = 10.dp,
                        vertical = 7.dp,
                    ),
            ) {
                Text(
                    "📎 " +
                        attachment.name,
                    style =
                        MaterialTheme.typography
                            .labelMedium,
                    maxLines = 1,
                )
                if (
                    attachment.sizeBytes > 0
                ) {
                    Text(
                        formatFileSize(
                            attachment.sizeBytes
                        ),
                        style =
                            MaterialTheme.typography
                                .labelSmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun formatFileSize(
    bytes: Long,
): String = when {
    bytes >= 1024L * 1024L ->
        String.format(
            "%.1f MB",
            bytes.toDouble() /
                (1024.0 * 1024.0),
        )
    bytes >= 1024L ->
        String.format(
            "%.1f KB",
            bytes.toDouble() / 1024.0,
        )
    else -> bytes.toString() + " B"
}
