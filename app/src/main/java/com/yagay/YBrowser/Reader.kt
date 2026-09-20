package com.yagay.YBrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

enum class ReaderBlockKind {
    HEADING,
    PARAGRAPH,
    QUOTE,
    LIST_ITEM,
}

data class ReaderBlock(
    val kind: ReaderBlockKind,
    val level: Int = 0,
    val text: String,
)

data class ReaderDocument(
    val title: String,
    val siteName: String,
    val sourceUrl: String,
    val blocks: List<ReaderBlock>,
)

object ReaderDocumentParser {
    fun parse(raw: String?): ReaderDocument? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.has("error")) return@runCatching null
            val blocksJson = root.optJSONArray("blocks") ?: return@runCatching null
            val blocks = buildList {
                for (index in 0 until minOf(blocksJson.length(), 600)) {
                    val item = blocksJson.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                        .replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(12_000)
                    if (text.length < 2) continue
                    val kind = when (item.optString("kind")) {
                        "heading" -> ReaderBlockKind.HEADING
                        "quote" -> ReaderBlockKind.QUOTE
                        "listitem" -> ReaderBlockKind.LIST_ITEM
                        else -> ReaderBlockKind.PARAGRAPH
                    }
                    add(
                        ReaderBlock(
                            kind = kind,
                            level = item.optInt("level", 0).coerceIn(0, 6),
                            text = text,
                        ),
                    )
                }
            }
            if (blocks.isEmpty()) return@runCatching null
            ReaderDocument(
                title = root.optString("title").ifBlank { "阅读模式" }.take(500),
                siteName = root.optString("siteName").take(200),
                sourceUrl = root.optString("sourceUrl").take(2_048),
                blocks = blocks,
            )
        }.getOrNull()
    }
}

@Composable
fun ReaderScreen(
    document: ReaderDocument,
    defaultTextScale: Int,
    onClose: () -> Unit,
    onOpenSource: () -> Unit,
) {
    var textScale by remember(document.sourceUrl) {
        mutableIntStateOf(defaultTextScale.coerceIn(75, 180))
    }
    val factor = textScale / 100f

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭阅读模式")
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        document.siteName.ifBlank { "阅读模式" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        document.title,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(onClick = onOpenSource) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = "返回原网页")
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                Text(
                    "字体 $textScale%",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(
                    value = textScale.toFloat(),
                    onValueChange = {
                        textScale = it.toInt().coerceIn(75, 180)
                    },
                    valueRange = 75f..180f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp),
            ) {
                item {
                    Text(
                        document.title,
                        modifier = Modifier.padding(top = 18.dp, bottom = 14.dp),
                        fontSize = (30 * factor).sp,
                        lineHeight = (38 * factor).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                items(document.blocks) { block ->
                    when (block.kind) {
                        ReaderBlockKind.HEADING -> {
                            val base = when (block.level) {
                                1 -> 25
                                2 -> 23
                                3 -> 21
                                else -> 19
                            }
                            Text(
                                block.text,
                                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                                fontSize = (base * factor).sp,
                                lineHeight = ((base + 7) * factor).sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        ReaderBlockKind.QUOTE -> {
                            Text(
                                block.text,
                                modifier = Modifier.padding(
                                    start = 14.dp,
                                    top = 8.dp,
                                    bottom = 10.dp,
                                ),
                                fontSize = (17 * factor).sp,
                                lineHeight = (27 * factor).sp,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        ReaderBlockKind.LIST_ITEM -> {
                            Row(Modifier.padding(vertical = 5.dp)) {
                                Text(
                                    "•",
                                    modifier = Modifier.padding(end = 9.dp),
                                    fontSize = (18 * factor).sp,
                                )
                                Text(
                                    block.text,
                                    modifier = Modifier.weight(1f),
                                    fontSize = (18 * factor).sp,
                                    lineHeight = (29 * factor).sp,
                                )
                            }
                        }

                        ReaderBlockKind.PARAGRAPH -> {
                            Text(
                                block.text,
                                modifier = Modifier.padding(vertical = 7.dp),
                                fontSize = (18 * factor).sp,
                                lineHeight = (29 * factor).sp,
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    TextButton(
                        onClick = onOpenSource,
                        modifier = Modifier.padding(vertical = 12.dp),
                    ) {
                        Text("打开原网页")
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

internal const val WEBVIEW_READER_EXTRACTION_SCRIPT = """
(() => {
  const clean = (value, maxLength) => (value || "")
    .replace(/[\\u0000-\\u001f\\u007f]+/g, " ")
    .replace(/\\s+/g, " ")
    .trim()
    .slice(0, maxLength);
  const source = document.querySelector("article") ||
    document.querySelector("main") ||
    document.body;
  if (!source) return JSON.stringify({ error: "missing-root" });
  const root = source.cloneNode(true);
  root.querySelectorAll(
    "script,style,noscript,template,iframe,object,embed,canvas,svg,form,input,button,nav,aside,footer,video,audio"
  ).forEach((node) => node.remove());
  const blocks = [];
  let totalChars = 0;
  root.querySelectorAll("h1,h2,h3,h4,h5,h6,p,blockquote,li").forEach((node) => {
    if (blocks.length >= 600 || totalChars >= 500000) return;
    const text = clean(
      node.innerText || node.textContent,
      Math.min(12000, 500000 - totalChars)
    );
    if (!text || text.length < 2) return;
    const tag = node.tagName.toLowerCase();
    const kind = tag.startsWith("h") ? "heading" :
      tag === "blockquote" ? "quote" :
      tag === "li" ? "listitem" : "paragraph";
    blocks.push({
      kind,
      level: kind === "heading" ? Number(tag.substring(1)) : 0,
      text,
    });
    totalChars += text.length;
  });
  return JSON.stringify({
    title: clean(document.querySelector('meta[property="og:title"]')?.content, 500) ||
      clean(document.title, 500),
    siteName: clean(document.querySelector('meta[property="og:site_name"]')?.content, 200) ||
      clean(location.hostname, 200),
    sourceUrl: location.href.slice(0, 2048),
    blocks,
  });
})()
"""
