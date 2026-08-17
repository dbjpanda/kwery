package dev.kwery.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import dev.kwery.QueryClient
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryState
import kotlinx.coroutines.flow.Flow

/**
 * The [QueryClient] available to composables.
 *
 * Provide it once, near the root:
 *
 * ```kotlin
 * CompositionLocalProvider(LocalQueryClient provides client) {
 *     App()
 * }
 * ```
 */
public val LocalQueryClient: ProvidableCompositionLocal<QueryClient> =
    staticCompositionLocalOf {
        error(
            "No QueryClient provided. Wrap your content in " +
                "CompositionLocalProvider(LocalQueryClient provides client) { … }",
        )
    }

/**
 * Observe [key] for as long as this composable is in the composition.
 *
 * ```kotlin
 * @Composable
 * fun TodoScreen(id: String) {
 *     val state = rememberQuery(TodoKey(id)) { api.todo(id) }
 *     when {
 *         state.isLoading -> Spinner()
 *         state.isError   -> ErrorView(state.error!!)
 *         else            -> TodoView(state.data!!, refreshing = state.isRefreshing)
 *     }
 * }
 * ```
 *
 * Two subtleties this handles so callers do not have to:
 *
 * - **[key] identity drives resubscription, and nothing else does.** `QueryKey`
 *   implementations are data classes, so an equal key across recompositions is
 *   the same key and the subscription is left alone. This is the Compose
 *   analogue of React's dependency array, and it needs no memoisation from the
 *   caller.
 * - **[fetcher] is captured, not observed.** A lambda is allocated fresh on
 *   every recomposition, so treating it as a subscription input would restart
 *   the query on every frame. It is read through [rememberUpdatedState], so the
 *   latest lambda is always used without ever being a reason to resubscribe.
 *
 * Leaving the composition detaches the observer. Re-entering within the client's
 * grace window — a rotation, a brief navigation — counts as the same mount, so
 * it neither refetches nor evicts.
 */
@Composable
public fun <T> rememberQuery(
    key: QueryKey<T>,
    options: QueryOptions = LocalQueryClient.current.config.defaultQueryOptions,
    fetcher: suspend () -> T,
): QueryState<T> {
    val client = LocalQueryClient.current
    val currentFetcher by rememberUpdatedState(fetcher)

    val flow: Flow<QueryState<T>> = remember(client, key, options) {
        client.query(key, options) { currentFetcher() }
    }

    // QueryState() is only shown for the frame before the cache's StateFlow
    // delivers its current value, which is immediate for a cached entry.
    return flow.collectAsState(initial = QueryState()).value
}

/**
 * Observe a projection of [key]'s data.
 *
 * The projection is deduplicated, so a selector narrowing to a count only
 * recomposes when the count changes.
 */
@Composable
public fun <T, R> rememberQuerySelecting(
    key: QueryKey<T>,
    options: QueryOptions = LocalQueryClient.current.config.defaultQueryOptions,
    select: (T?) -> R,
    fetcher: suspend () -> T,
): State<R> {
    val client = LocalQueryClient.current
    val currentFetcher by rememberUpdatedState(fetcher)
    val currentSelect by rememberUpdatedState(select)

    val flow = remember(client, key, options) {
        client.query(key, options, select = { currentSelect(it) }) { currentFetcher() }
    }

    return flow.collectAsState(initial = remember(key) { currentSelect(null) })
}
