package com.yagay.YBrowser

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BrowserHomePage(
    searchEngine: SearchEngine,
    bookmarks: List<BookmarkEntry>,
    history: List<HistoryEntry>,
    privateMode: Boolean,
    onNavigate: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val suggestions = remember(query, bookmarks, history) {
        localAddressSuggestions(
            query = query,
            bookmarks = bookmarks,
            history = history,
            limit = 6,
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Spacer(Modifier.height(42.dp))
                Text(
                    "YBrowser",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (privateMode) {
                        "隐私新标签 · ${searchEngine.label}"
                    } else {
                        "新标签 · ${searchEngine.label}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(22.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    placeholder = { Text("搜索或输入网址") },
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            if (query.isNotBlank()) onNavigate(query)
                        },
                    ),
                )
            }

            if (query.isNotBlank() && suggestions.isNotEmpty()) {
                items(suggestions, key = { it.url }) { suggestion ->
                    ListItem(
                        headlineContent = {
                            Text(
                                suggestion.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                suggestion.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (suggestion.bookmarked) {
                                    Icons.Outlined.Bookmark
                                } else {
                                    Icons.Outlined.History
                                },
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clickable { onNavigate(suggestion.url) },
                    )
                }
            }

            if (query.isBlank() && bookmarks.isNotEmpty()) {
                item {
                    Text(
                        "快捷收藏",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        maxItemsInEachRow = 4,
                    ) {
                        bookmarks.take(8).forEach { bookmark ->
                            Surface(
                                modifier = Modifier
                                    .size(width = 78.dp, height = 82.dp)
                                    .clickable { onNavigate(bookmark.url) },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Box(
                                        modifier = Modifier.size(30.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            homeInitial(bookmark),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(
                                        bookmark.title.ifBlank {
                                            homeHost(bookmark.url)
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (query.isBlank() && !privateMode && history.isNotEmpty()) {
                item {
                    Text(
                        "最近访问",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(history.take(8), key = { it.url }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                entry.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                homeHost(entry.url),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.History, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clickable { onNavigate(entry.url) },
                    )
                }
            }

            item { Spacer(Modifier.height(36.dp)) }
        }
    }
}

data class BrowserAddressSuggestion(
    val title: String,
    val url: String,
    val bookmarked: Boolean,
)

fun localAddressSuggestions(
    query: String,
    bookmarks: List<BookmarkEntry>,
    history: List<HistoryEntry>,
    limit: Int = 6,
): List<BrowserAddressSuggestion> {
    val needle = query.trim()
    if (needle.isBlank() || needle.startsWith(">")) return emptyList()

    val bookmarkUrls = bookmarks.map { it.url }.toSet()
    return (bookmarks.asSequence().map {
        BrowserAddressSuggestion(
            title = it.title.ifBlank { it.url },
            url = it.url,
            bookmarked = true,
        )
    } + history.asSequence().map {
        BrowserAddressSuggestion(
            title = it.title.ifBlank { it.url },
            url = it.url,
            bookmarked = it.url in bookmarkUrls,
        )
    })
        .filter {
            it.title.contains(needle, ignoreCase = true) ||
                it.url.contains(needle, ignoreCase = true)
        }
        .distinctBy { it.url }
        .take(limit)
        .toList()
}

private fun homeInitial(bookmark: BookmarkEntry): String =
    bookmark.title.trim().take(1).ifBlank {
        homeHost(bookmark.url).take(1)
    }.uppercase()

private fun homeHost(url: String): String =
    runCatching {
        Uri.parse(url).host?.removePrefix("www.").orEmpty()
    }.getOrDefault("").ifBlank { url }
