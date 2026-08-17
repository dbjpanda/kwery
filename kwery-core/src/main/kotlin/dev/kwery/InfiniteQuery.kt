package dev.kwery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Accumulated pages of an infinite query.
 *
 * [pages] and [pageParams] are positionally aligned: `pageParams[i]` is the
 * parameter that produced `pages[i]`. Every operation preserves that, because
 * a misaligned pair silently refetches the wrong page.
 */
public data class InfiniteData<P, T>(
    val pages: List<T> = emptyList(),
    val pageParams: List<P> = emptyList(),
) {
    init {
        require(pages.size == pageParams.size) {
            "pages (${pages.size}) and pageParams (${pageParams.size}) must stay aligned"
        }
    }

    public val isEmpty: Boolean get() = pages.isEmpty()
}

/**
 * How an infinite query refetches once its pages are stale.
 *
 * TanStack only does [AllPages]. That is correct for cursor APIs, but it means
 * a user who scrolled forty pages triggers **forty sequential requests** on the
 * next refetch — slow, expensive on cellular, and a load spike across a fleet.
 * The cheaper strategies exist because most REST pagination does not need the
 * strict version.
 */
public sealed interface RefetchStrategy {
    /**
     * Refetch every page, sequentially, from the first.
     *
     * The default, and the only correct choice for **cursor**-based APIs: each
     * page's parameter comes from the previous page's response, so a stale
     * cursor can duplicate or skip records.
     */
    public data object AllPages : RefetchStrategy

    /**
     * Refetch only the first page; keep the rest as cached.
     *
     * Correct for offset- or id-based pagination, where pages are independent.
     * One request instead of forty.
     */
    public data object FirstPageOnly : RefetchStrategy

    /** Refetch the first [pages] pages. */
    public data class Windowed(val pages: Int) : RefetchStrategy {
        init {
            require(pages > 0) { "windowed refetch needs at least one page, was $pages" }
        }
    }
}

public class InfiniteQueryOptions<P, T>(
    /** The parameter for the first page. Required — there is no sensible default. */
    public val initialPageParam: P,

    /**
     * The parameter for the page after [lastPage], or null when there are no
     * more. Null is what makes [InfiniteQuery.hasNextPage] false.
     */
    public val getNextPageParam: (lastPage: T, allPages: List<T>, lastPageParam: P) -> P?,

    /** Supply to enable backwards paging. */
    public val getPreviousPageParam: (
        (firstPage: T, allPages: List<T>, firstPageParam: P) -> P?
    )? = null,

    /**
     * Cap on retained pages. Bounds both memory and the cost of an [AllPages]
     * refetch. Fetching forward evicts from the front; backward, from the back.
     */
    public val maxPages: Int? = null,

    public val refetchStrategy: RefetchStrategy = RefetchStrategy.AllPages,
)

private enum class PageDirection { Refresh, Next, Previous }

/**
 * A query whose pages accumulate under one key.
 *
 * ```kotlin
 * val feed = client.infiniteQuery(
 *     key = FeedKey,
 *     options = InfiniteQueryOptions(
 *         initialPageParam = 0,
 *         getNextPageParam = { last, _, _ -> last.nextCursor },
 *     ),
 * ) { cursor -> api.feed(cursor) }
 *
 * feed.state.collect { … }
 * feed.fetchNextPage()
 * ```
 *
 * Note the distinction from **paginated** queries, where one page replaces the
 * last: those need nothing new — an ordinary query whose key contains the page
 * number, plus `PlaceholderData.KeepPrevious` so the list does not flash empty.
 */
public class InfiniteQuery<P, T> internal constructor(
    private val client: QueryClient,
    private val key: QueryKey<InfiniteData<P, T>>,
    private val options: InfiniteQueryOptions<P, T>,
    private val queryOptions: QueryOptions,
    private val fetchPage: suspend (pageParam: P) -> T,
) {
    private val directionLock = Mutex()
    private var direction = PageDirection.Refresh

    /** The accumulated pages, with the usual query status axes. */
    public val state: Flow<QueryState<InfiniteData<P, T>>> =
        client.query(key, queryOptions) { runFetch() }

    /** True when [getNextPageParam] can produce another page parameter. */
    public suspend fun hasNextPage(): Boolean {
        val data = client.getQueryData(key) ?: return true
        if (data.isEmpty) return true
        return nextParam(data) != null
    }

    /** True when backwards paging is configured and can produce a parameter. */
    public suspend fun hasPreviousPage(): Boolean {
        val data = client.getQueryData(key) ?: return false
        if (data.isEmpty) return false
        return previousParam(data) != null
    }

    /** Append the next page, if there is one. */
    public suspend fun fetchNextPage(): Unit = fetchInDirection(PageDirection.Next)

    /** Prepend the previous page, if backwards paging is configured. */
    public suspend fun fetchPreviousPage(): Unit = fetchInDirection(PageDirection.Previous)

    /**
     * Overlapping page fetches are **conflated**, not queued.
     *
     * All pages live in one cache entry, and the entry deduplicates in-flight
     * fetches — so a second `fetchNextPage` while one is running joins the
     * existing request rather than starting a competing one. A scroll listener
     * that fires twice therefore costs nothing.
     *
     * TanStack instead exposes `cancelRefetch` and documents "check isFetching
     * first" as the caller's job. Kwery has no equivalent option because there
     * is nothing to opt out of: the safe behaviour is structural.
     */
    private suspend fun fetchInDirection(target: PageDirection) {
        directionLock.withLock { direction = target }
        client.refetchQueries(QueryFilters(exactKey = key))
    }

    private suspend fun runFetch(): InfiniteData<P, T> {
        val current = client.getQueryData(key) ?: InfiniteData()
        val requested = directionLock.withLock {
            direction.also { direction = PageDirection.Refresh }
        }

        return when {
            current.isEmpty -> loadFirstPage()
            requested == PageDirection.Next -> appendNextPage(current)
            requested == PageDirection.Previous -> prependPreviousPage(current)
            else -> refresh(current)
        }
    }

    private suspend fun loadFirstPage(): InfiniteData<P, T> {
        val param = options.initialPageParam
        return InfiniteData(listOf(fetchPage(param)), listOf(param))
    }

    private suspend fun appendNextPage(current: InfiniteData<P, T>): InfiniteData<P, T> {
        val param = nextParam(current) ?: return current
        val page = fetchPage(param)
        return trim(
            InfiniteData(current.pages + page, current.pageParams + param),
            evictFrom = Evict.Front,
        )
    }

    private suspend fun prependPreviousPage(current: InfiniteData<P, T>): InfiniteData<P, T> {
        val param = previousParam(current) ?: return current
        val page = fetchPage(param)
        return trim(
            InfiniteData(listOf(page) + current.pages, listOf(param) + current.pageParams),
            evictFrom = Evict.Back,
        )
    }

    /**
     * Re-fetch existing pages according to [RefetchStrategy].
     *
     * [RefetchStrategy.AllPages] refetches **sequentially, from the first**, and
     * re-derives each subsequent parameter from the page just received rather
     * than reusing the stored one — that is the point of the strict strategy,
     * since a stale cursor can duplicate or skip records.
     */
    private suspend fun refresh(current: InfiniteData<P, T>): InfiniteData<P, T> {
        val target = when (val strategy = options.refetchStrategy) {
            RefetchStrategy.AllPages -> current.pages.size
            RefetchStrategy.FirstPageOnly -> 1
            is RefetchStrategy.Windowed -> minOf(strategy.pages, current.pages.size)
        }

        val pages = mutableListOf<T>()
        val params = mutableListOf<P>()
        var param: P? = current.pageParams.firstOrNull() ?: options.initialPageParam

        while (pages.size < target && param != null) {
            val page = fetchPage(param)
            pages += page
            params += param
            param = options.getNextPageParam(page, pages.toList(), param)
        }

        // Pages beyond the refreshed window keep their cached values, so a
        // cheap strategy does not discard what the user already scrolled past.
        if (pages.size < current.pages.size) {
            pages += current.pages.drop(pages.size)
            params += current.pageParams.drop(params.size)
        }

        return InfiniteData(pages, params)
    }

    private fun nextParam(data: InfiniteData<P, T>): P? {
        val lastPage = data.pages.lastOrNull() ?: return options.initialPageParam
        return options.getNextPageParam(lastPage, data.pages, data.pageParams.last())
    }

    private fun previousParam(data: InfiniteData<P, T>): P? {
        val getPrevious = options.getPreviousPageParam ?: return null
        val firstPage = data.pages.firstOrNull() ?: return null
        return getPrevious(firstPage, data.pages, data.pageParams.first())
    }

    private enum class Evict { Front, Back }

    /** Enforce [InfiniteQueryOptions.maxPages], evicting from the far end. */
    private fun trim(data: InfiniteData<P, T>, evictFrom: Evict): InfiniteData<P, T> {
        val max = options.maxPages ?: return data
        if (data.pages.size <= max) return data
        val excess = data.pages.size - max
        return when (evictFrom) {
            // Paging forward drops the oldest pages, and vice versa — always
            // dropping from the end the user is moving away from.
            Evict.Front -> InfiniteData(data.pages.drop(excess), data.pageParams.drop(excess))
            Evict.Back -> InfiniteData(
                data.pages.dropLast(excess),
                data.pageParams.dropLast(excess),
            )
        }
    }
}

/**
 * Create an infinite query.
 *
 * @param fetchPage fetches one page for a given page parameter.
 */
public fun <P, T> QueryClient.infiniteQuery(
    key: QueryKey<InfiniteData<P, T>>,
    options: InfiniteQueryOptions<P, T>,
    queryOptions: QueryOptions = config.defaultQueryOptions,
    fetchPage: suspend (pageParam: P) -> T,
): InfiniteQuery<P, T> = InfiniteQuery(this, key, options, queryOptions, fetchPage)

/** Flatten accumulated pages, given how to read items out of one. */
public fun <P, T, I> InfiniteData<P, T>.flatten(items: (T) -> List<I>): List<I> =
    pages.flatMap(items)
