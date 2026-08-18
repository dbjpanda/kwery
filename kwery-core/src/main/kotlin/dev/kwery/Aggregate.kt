package dev.kwery

/**
 * One answer for a screen that is waiting on several queries.
 *
 * `combine` over several query flows gives a `List<QueryState<T>>`, but a screen
 * needs a single verdict: am I loading, did anything fail, do I have everything?
 * Writing that by hand is easy to get subtly wrong, most often by treating "any
 * pending" as loading when one of the queries is disabled and will never
 * resolve.
 */
public data class AggregateState<T>(
    /** Present for every query that has data; null entries are still missing. */
    val data: List<T?>,
    val status: QueryStatus,
    val fetchStatus: FetchStatus,
    /** The first error encountered, if any. */
    val error: Throwable? = null,
) {
    public val isPending: Boolean get() = status == QueryStatus.Pending
    public val isError: Boolean get() = status == QueryStatus.Error
    public val isSuccess: Boolean get() = status == QueryStatus.Success
    public val isFetching: Boolean get() = fetchStatus == FetchStatus.Fetching
    public val isPaused: Boolean get() = fetchStatus == FetchStatus.Paused

    /** True only when every query is loading for the first time. */
    public val isLoading: Boolean get() = isPending && isFetching
}

/**
 * Reduce several query states to one.
 *
 * ```kotlin
 * combine(ids.map { client.query(TodoKey(it)) { api.todo(it) } }) { it.toList() }
 *     .map { it.aggregate() }
 * ```
 *
 * Semantics, each chosen rather than fallen into:
 *
 * - **`status`** is `Error` if *any* query errored (first error wins), `Success`
 *   if *all* succeeded, otherwise `Pending`. A screen cannot claim to be ready
 *   while part of it is missing.
 * - **`fetchStatus`** is `Fetching` if any is fetching, else `Paused` if any is
 *   paused, else `Idle`. Fetching outranks paused because something is in fact
 *   happening.
 * - **Partial data is preserved**, so a screen can render what it has instead of
 *   blanking on one slow query.
 * - **[skipDisabled]** excludes queries that will never resolve. A single
 *   disabled query would otherwise hold the whole screen in `Pending` for ever
 *   — almost never what anyone means, which is why it defaults to true.
 */
public fun <T> List<QueryState<T>>.aggregate(
    skipDisabled: Boolean = true,
    isDisabled: (QueryState<T>) -> Boolean = { it.isPending && it.fetchStatus == FetchStatus.Idle },
): AggregateState<T> {
    val considered = if (skipDisabled) filterNot(isDisabled) else this

    val status = when {
        considered.any { it.isError } -> QueryStatus.Error
        considered.isEmpty() || considered.all { it.isSuccess } -> QueryStatus.Success
        else -> QueryStatus.Pending
    }

    val fetchStatus = when {
        any { it.isFetching } -> FetchStatus.Fetching
        any { it.isPaused } -> FetchStatus.Paused
        else -> FetchStatus.Idle
    }

    return AggregateState(
        data = map { it.data },
        status = status,
        fetchStatus = fetchStatus,
        error = firstOrNull { it.isError }?.error,
    )
}
