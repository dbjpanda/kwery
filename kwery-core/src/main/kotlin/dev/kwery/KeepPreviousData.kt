package dev.kwery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Keep showing the previous key's data while a new key loads.
 *
 * This is what stops a paginated list flashing empty every time the user turns
 * a page. Page 2 is a different cache entry from page 1, so without it the
 * screen goes from "page 1" to "nothing" to "page 2" — a full-height layout
 * jump for every tap.
 *
 * ```kotlin
 * val todos = page
 *     .flatMapLatest { p -> client.query(TodoPageKey(p)) { api.todos(p) } }
 *     .keepPreviousData()
 * ```
 *
 * While the new key has no data, the previous data is emitted with
 * [QueryState.isPlaceholderData] set, and `status` reported as
 * [QueryStatus.Success] — because there *is* something on screen — while
 * `fetchStatus` still says `Fetching`. Use `isPlaceholderData` to grey the list
 * out, and the real data replaces it as soon as it arrives.
 *
 * TanStack models this as a `placeholderData` option on the query. Here it is a
 * `Flow` operator, because "previous" is a property of **this observer's**
 * history and not of the cache entry: two screens watching the same key can
 * legitimately have different previous values, and the cache has no opinion
 * about either.
 *
 * Nothing is written to the cache — the placeholder exists only in this stream.
 */
public fun <T> Flow<QueryState<T>>.keepPreviousData(): Flow<QueryState<T>> = flow {
    var previous: T? = null
    var previousUpdatedAt: Long? = null

    collect { state ->
        val data = state.data
        if (data != null) {
            previous = data
            previousUpdatedAt = state.dataUpdatedAt
            emit(state)
            return@collect
        }

        val fallback = previous
        if (fallback == null || state.isError) {
            // Nothing to fall back on, or a genuine failure the caller must
            // see. A stale page hiding an error would be worse than a gap.
            emit(state)
            return@collect
        }

        emit(
            state.copy(
                data = fallback,
                status = QueryStatus.Success,
                dataUpdatedAt = previousUpdatedAt,
                isPlaceholderData = true,
            ),
        )
    }
}
