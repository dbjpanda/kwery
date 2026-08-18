package dev.kwery

/**
 * Load [key] into the cache ahead of time, ignoring the result.
 *
 * **Never throws.** A speculative fetch that fails must not surface to the user
 * or crash a scroll handler — nothing is waiting on it. The failure is recorded
 * in that query's own state, where the screen will find it if it ever asks.
 *
 * Respects `staleTime`, so prefetching data that is already fresh issues no
 * request. That is what makes it safe to call on every scroll tick:
 *
 * ```kotlin
 * // In a click handler, before navigating — the request overlaps the
 * // transition animation, which is usually 200-300ms of free latency.
 * onClick = {
 *     scope.launch { client.prefetchQuery(TodoKey(id)) { api.todo(id) } }
 *     navigate(id)
 * }
 * ```
 */
public suspend fun <T> QueryClient.prefetchQuery(
    key: QueryKey<T>,
    options: QueryOptions = config.defaultQueryOptions,
    fetcher: suspend () -> T,
) {
    runCatching { fetchQuery(key, options, fetcher) }
}

/**
 * Fetch [key] and return its data, **throwing** on failure.
 *
 * Use when the result is needed imperatively — a deep link that must resolve
 * before a screen can be built. For speculative loading use [prefetchQuery],
 * which swallows failures.
 *
 * Always fetches, even if the cached data is fresh.
 */
public suspend fun <T> QueryClient.fetchQuery(
    key: QueryKey<T>,
    options: QueryOptions = config.defaultQueryOptions,
    fetcher: suspend () -> T,
): T = obtainForFetch(key, options, fetcher).fetchAndAwait(force = true)

/**
 * Return [key]'s cached data, fetching only if it is missing or stale.
 *
 * The read-through form: cheap when the cache is warm, correct when it is not.
 */
public suspend fun <T> QueryClient.ensureQueryData(
    key: QueryKey<T>,
    options: QueryOptions = config.defaultQueryOptions,
    fetcher: suspend () -> T,
): T = obtainForFetch(key, options, fetcher).fetchAndAwait(force = false)
