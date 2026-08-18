package dev.kwery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Load the first [pages] pages of an infinite query into the cache ahead of
 * time, ignoring the result.
 *
 * The infinite counterpart of [prefetchQuery], and it behaves the same way:
 * **never throws**, and respects `staleTime`, so calling it on a scroll tick or
 * in a click handler is safe.
 *
 * [pages] defaults to one because the destination screen shows one page. Ask
 * for more only when you know the user will scroll — every extra page is a
 * request for data nobody has looked at yet.
 *
 * ```kotlin
 * onClick = {
 *     scope.launch {
 *         client.prefetchInfiniteQuery(FeedKey, feedOptions) { page -> api.feed(page) }
 *     }
 *     navigate("feed")
 * }
 * ```
 */
public suspend fun <P : Any, T> QueryClient.prefetchInfiniteQuery(
    key: QueryKey<InfiniteData<P, T>>,
    options: InfiniteQueryOptions<P, T>,
    queryOptions: QueryOptions = config.defaultQueryOptions,
    pages: Int = 1,
    fetchPage: suspend (pageParam: P) -> T,
) {
    runCatching { ensureInfiniteQueryData(key, options, queryOptions, pages, fetchPage) }
}

/**
 * Return an infinite query's cached pages, fetching the first [pages] only if
 * they are missing or stale.
 *
 * The read-through form, and the one that throws — a caller is waiting on it.
 */
public suspend fun <P : Any, T> QueryClient.ensureInfiniteQueryData(
    key: QueryKey<InfiniteData<P, T>>,
    options: InfiniteQueryOptions<P, T>,
    queryOptions: QueryOptions = config.defaultQueryOptions,
    pages: Int = 1,
    fetchPage: suspend (pageParam: P) -> T,
): InfiniteData<P, T> {
    require(pages > 0) { "pages must be at least 1, was $pages" }

    // Retry is applied per page, exactly as InfiniteQuery does it, and the
    // entry itself never retries. Otherwise one failing page restarts the whole
    // page walk and refetches the pages that already succeeded.
    val entryOptions = queryOptions.copy(retry = RetryPolicy.Never)

    val walk: suspend () -> InfiniteData<P, T> = {
        val collected = mutableListOf<T>()
        val params = mutableListOf<P>()
        var param: P? = options.initialPageParam

        while (param != null && collected.size < pages) {
            val page = fetchPageWithRetry(queryOptions, param, fetchPage)
            collected += page
            params += param
            param = options.getNextPageParam(page, collected.toList(), param)
        }
        InfiniteData(collected.toList(), params.toList())
    }

    return obtainForFetch(key, entryOptions, walk).fetchAndAwait(force = false)
}

private suspend fun <P : Any, T> fetchPageWithRetry(
    queryOptions: QueryOptions,
    pageParam: P,
    fetchPage: suspend (pageParam: P) -> T,
): T {
    var failureCount = 0
    while (true) {
        try {
            return fetchPage(pageParam)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (!queryOptions.retry.shouldRetry(failureCount, error)) throw error
            val wait = queryOptions.retryDelay.delayFor(failureCount, error)
            failureCount++
            delay(wait)
        }
    }
}
