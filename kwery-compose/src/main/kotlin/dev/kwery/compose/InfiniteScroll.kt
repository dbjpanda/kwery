package dev.kwery.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import dev.kwery.InfiniteQuery
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Fetch the next page as the list approaches its end.
 *
 * ```kotlin
 * val listState = rememberLazyListState()
 * LazyColumn(state = listState) { items(todos) { … } }
 * feed.FetchNextPageWhenNearEnd(listState)
 * ```
 *
 * Every infinite list needs this wiring and it is easy to get subtly wrong —
 * reading the scroll position directly in composition rather than through
 * [snapshotFlow] recomposes on every scrolled pixel.
 *
 * Overlapping requests need no guard here: all pages share one cache entry, and
 * the entry deduplicates in-flight fetches, so firing repeatedly while a page
 * is loading costs one request.
 *
 * @param threshold how many items from the end to trigger at.
 */
@Composable
public fun <P : Any, T> InfiniteQuery<P, T>.FetchNextPageWhenNearEnd(
    listState: LazyListState,
    threshold: Int = 3,
) {
    val shouldFetch = remember(listState, threshold) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= layout.totalItemsCount - 1 - threshold
        }
    }

    LaunchedEffect(this, shouldFetch) {
        snapshotFlow { shouldFetch.value }
            .distinctUntilChanged()
            .filter { it }
            .collect { if (hasNextPage()) fetchNextPage() }
    }
}

/** Grid equivalent of [FetchNextPageWhenNearEnd]. */
@Composable
public fun <P : Any, T> InfiniteQuery<P, T>.FetchNextPageWhenNearEnd(
    gridState: LazyGridState,
    threshold: Int = 6,
) {
    val shouldFetch = remember(gridState, threshold) {
        derivedStateOf {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= layout.totalItemsCount - 1 - threshold
        }
    }

    LaunchedEffect(this, shouldFetch) {
        snapshotFlow { shouldFetch.value }
            .distinctUntilChanged()
            .filter { it }
            .collect { if (hasNextPage()) fetchNextPage() }
    }
}
