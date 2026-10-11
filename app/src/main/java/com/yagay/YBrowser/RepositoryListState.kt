package com.yagay.YBrowser

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared IO-backed list state for bottom sheets. Never synchronously scan disk during composition. */
internal class RepositoryListState<T>(
    val items: List<T>,
    val loading: Boolean,
    val failed: Boolean,
    val refresh: () -> Unit,
)

@Composable
internal fun <T> rememberRepositoryList(
    context: Context,
    loader: (Context) -> List<T>,
): RepositoryListState<T> {
    var revision by remember(context) { mutableIntStateOf(0) }
    var items by remember(context) { mutableStateOf<List<T>>(emptyList()) }
    var loading by remember(context) { mutableStateOf(true) }
    var failed by remember(context) { mutableStateOf(false) }
    val latestLoader by rememberUpdatedState(loader)
    LaunchedEffect(context, revision) {
        loading = true
        try {
            val loaded = withContext(Dispatchers.IO) { latestLoader(context) }
            items = loaded
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the last successful snapshot on transient storage errors.
            failed = true
        } finally {
            loading = false
        }
    }
    return RepositoryListState(items, loading, failed) { revision++ }
}
