package dev.kwery.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.kwery.InfiniteData
import dev.kwery.InfiniteQuery
import dev.kwery.InfiniteQueryOptions
import dev.kwery.Mutation
import dev.kwery.MutationOptions
import dev.kwery.MutationState
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryState
import dev.kwery.infiniteQuery

/**
 * Create a mutation scoped to this composable.
 *
 * The mutation is created once per [key]; recomposition returns the same
 * instance, so a pending write is not lost when the screen redraws.
 *
 * ```kotlin
 * val addTodo = rememberMutation { MutationOptions(mutationFn = { api.addTodo(it) }) }
 * val state by addTodo.state.collectAsState()
 *
 * Button(onClick = { addTodo.mutate("Buy milk") }, enabled = !state.isPending) {
 *     Text(if (state.isPending) "Saving…" else "Save")
 * }
 * ```
 *
 * @param key recreate the mutation when this changes. Rarely needed.
 */
@Composable
public fun <V, R, C> rememberMutation(
    key: Any? = Unit,
    options: () -> MutationOptions<V, R, C>,
): Mutation<V, R>? {
    val client = LocalQueryClient.current
    val currentOptions by rememberUpdatedState(options)

    // Creating a mutation acquires its scope lock, which suspends — so it
    // cannot happen during composition. The value is null for the first frame.
    val created = produceState<Mutation<V, R>?>(initialValue = null, client, key) {
        value = client.mutation(currentOptions())
    }
    return created.value
}

/** Observe a mutation's state, recomposing as it progresses. */
@Composable
public fun <V, R> Mutation<V, R>.stateAsState(): State<MutationState<V, R>> =
    state.collectAsState()

/**
 * Create an infinite query scoped to this composable.
 *
 * ```kotlin
 * val feed = rememberInfiniteQuery(
 *     key = FeedKey,
 *     options = InfiniteQueryOptions(
 *         initialPageParam = 0,
 *         getNextPageParam = { last, _, _ -> last.nextCursor },
 *     ),
 * ) { cursor -> api.feed(cursor) }
 * ```
 *
 * As with [rememberQuery], the page fetcher is captured rather than observed,
 * so a lambda reallocated on every recomposition does not recreate the query.
 */
@Composable
public fun <P : Any, T> rememberInfiniteQuery(
    key: QueryKey<InfiniteData<P, T>>,
    options: InfiniteQueryOptions<P, T>,
    queryOptions: QueryOptions = LocalQueryClient.current.config.defaultQueryOptions,
    fetchPage: suspend (pageParam: P) -> T,
): InfiniteQuery<P, T> {
    val client = LocalQueryClient.current
    val currentFetch by rememberUpdatedState(fetchPage)
    return remember(client, key, options, queryOptions) {
        client.infiniteQuery(key, options, queryOptions) { currentFetch(it) }
    }
}

/** Observe an infinite query's accumulated pages. */
@Composable
public fun <P : Any, T> InfiniteQuery<P, T>.stateAsState(): State<QueryState<InfiniteData<P, T>>> =
    state.collectAsState(initial = QueryState())

/**
 * How many queries are fetching right now, across the whole cache.
 *
 * For a global activity indicator — a progress bar in a toolbar, say — without
 * every screen reporting its own loading state upward.
 */
@Composable
public fun rememberIsFetching(): State<Int> =
    LocalQueryClient.current.isFetching.collectAsState()

/**
 * True while a persisted cache is being restored.
 *
 * Queries hold rather than fetch during a restore, so a splash screen shown on
 * this flag will not be followed by a second load.
 */
@Composable
public fun rememberIsRestoring(): State<Boolean> =
    LocalQueryClient.current.isRestoring.collectAsState()
