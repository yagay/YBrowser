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
import androidx.compose.runtime.LaunchedEffect
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
import java.net.URL
import java.net.HttpURLConnection
import org.json.JSONArray
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers

@Composable
fun BrowserHomePage(
    searchEngine: SearchEngine,
    bookmarks: List<BookmarkEntry>,
    history: List<HistoryEntry>,
    privateMode: Boolean,
    historySuggestionsEnabled: Boolean = true,
    bookmarkSuggestionsEnabled: Boolean = true,
    onlineSuggestionsEnabled: Boolean = true,
    onNavigate: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var onlineSuggestions by remember {
        mutableStateOf<List<String>>(emptyList())
    }

    LaunchedEffect(
        query,
        searchEngine,
        onlineSuggestionsEnabled,
        privateMode,
    ) {
        onlineSuggestions = emptyList()
        val trimmed = query.trim()
        if (
            privateMode ||
            !onlineSuggestionsEnabled ||
            trimmed.length < 2 ||
            trimmed.startsWith(">") ||
            trimmed.contains("://")
        ) {
            return@LaunchedEffect
        }
        delay(180)
        onlineSuggestions =
            fetchOnlineSearchSuggestions(
                searchEngine,
                trimmed,
            )
    }

    val suggestions =
        remember(
            query,
            bookmarks,
            history,
            onlineSuggestions,
            historySuggestionsEnabled,
            bookmarkSuggestionsEnabled,
        ) {
            val local =
                localAddressSuggestions(
                    query = query,
                    bookmarks =
                        if (bookmarkSuggestionsEnabled) {
                            bookmarks
                        } else {
                            emptyList()
                        },
                    history =
                        if (historySuggestionsEnabled) {
                            history
                        } else {
                            emptyList()
                        },
                    limit = 6,
                )
            (
                local +
                    onlineSuggestions.map {
                        BrowserAddressSuggestion(
                            title = it,
                            url = it,
                            bookmarked = false,
                            online = true,
                        )
                    }
                )
                .distinctBy {
                    it.url.lowercase()
                }
                .take(6)
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
    val online: Boolean = false,
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

suspend fun fetchOnlineSearchSuggestions(
    searchEngine: SearchEngine,
    query: String,
): List<String> =
    withContext(Dispatchers.IO) {
        val endpoint =
            when (searchEngine) {
                SearchEngine.GOOGLE ->
                    "https://suggestqueries.google.com/complete/search?client=firefox&q=" +
                        Uri.encode(query)

                SearchEngine.DUCKDUCKGO ->
                    "https://duckduckgo.com/ac/?type=list&q=" +
                        Uri.encode(query)

                SearchEngine.BING ->
                    "https://api.bing.com/osjson.aspx?query=" +
                        Uri.encode(query)

                else ->
                    return@withContext emptyList()
            }

        runCatching {
            val connection =
                (URL(endpoint).openConnection() as HttpURLConnection)
                    .apply {
                        connectTimeout = 2500
                        readTimeout = 2500
                        instanceFollowRedirects = true
                        setRequestProperty(
                            "Accept",
                            "application/json",
                        )
                        setRequestProperty(
                            "User-Agent",
                            "YBrowser/1.0",
                        )
                    }
            try {
                if (connection.responseCode !in 200..299) {
                    return@runCatching emptyList()
                }
                val raw =
                    connection.inputStream
                        .bufferedReader(
                            Charsets.UTF_8
                        )
                        .use { it.readText() }
                        .take(256 * 1024)
                val root = JSONArray(raw)
                val values =
                    when (searchEngine) {
                        SearchEngine.DUCKDUCKGO ->
                            buildList {
                                for (
                                    index in
                                    0 until root.length()
                                ) {
                                    val phrase =
                                        root.optJSONObject(
                                            index
                                        )
                                            ?.optString(
                                                "phrase"
                                            )
                                            .orEmpty()
                                            .trim()
                                    if (
                                        phrase.isNotBlank()
                                    ) {
                                        add(phrase)
                                    }
                                }
                            }

                        else -> {
                            val array =
                                root.optJSONArray(1)
                                    ?: JSONArray()
                            buildList {
                                for (
                                    index in
                                    0 until array.length()
                                ) {
                                    array.optString(
                                        index
                                    )
                                        .trim()
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                        ?.let(::add)
                                }
                            }
                        }
                    }
                values.distinct().take(6)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(emptyList())
    }

private fun homeInitial(bookmark: BookmarkEntry): String =
    bookmark.title.trim().take(1).ifBlank {
        homeHost(bookmark.url).take(1)
    }.uppercase()

private fun homeHost(url: String): String =
    runCatching {
        Uri.parse(url).host?.removePrefix("www.").orEmpty()
    }.getOrDefault("").ifBlank { url }
