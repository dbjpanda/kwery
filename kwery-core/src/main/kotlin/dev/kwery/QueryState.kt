package dev.kwery

/**
 * Whether this query has data. Orthogonal to [FetchStatus].
 *
 * See [QueryState] for why the two are separate.
 */
public enum class QueryStatus {
    /** No data yet. Note this does **not** mean a request is running. */
    Pending,

    /** The last attempt failed. Previously loaded [QueryState.data] is retained. */
    Error,

    /** Data is available. */
    Success,
}

/**
 * Whether the query function is running. Orthogonal to [QueryStatus].
 */
public enum class FetchStatus {
    /** A request is in flight. */
    Fetching,

    /** The query wants to fetch but is paused — normally for lack of connectivity. */
    Paused,

    /** Neither fetching nor paused. */
    Idle,
}

/**
 * The observable state of a query.
 *
 * Two orthogonal axes, deliberately not merged into one sealed hierarchy:
 *
 * - [status] answers *do I have data?*
 * - [fetchStatus] answers *is the query function running?*
 *
 * Every combination is reachable, and the interesting ones are exactly the
 * states a single enum cannot express: `Success` + `Fetching` is a background
 * refresh with content on screen, and `Pending` + `Paused` is a cold start with
 * no connectivity. Collapsing them into `Loading | Success | Error` loses both.
 *
 * Use [toUiState] if an exhaustive `when` is wanted at the UI boundary; the
 * lossy projection belongs there rather than in the core.
 */
public data class QueryState<T>(
    /**
     * The last successfully loaded data, if any.
     *
     * **Retained when [status] becomes [QueryStatus.Error].** A failed
     * background refetch must not blank a screen that is showing valid content,
     * so `status == Error` does **not** imply `data == null`.
     */
    val data: T? = null,

    /** The error from the last failed attempt, after retries are exhausted. */
    val error: Throwable? = null,

    val status: QueryStatus = QueryStatus.Pending,

    val fetchStatus: FetchStatus = FetchStatus.Idle,

    /** Consecutive failures for the attempt currently in progress. */
    val failureCount: Int = 0,

    /**
     * The error from the most recent failed attempt *while retries continue*.
     * [error] stays null until the final attempt fails, so a UI can show
     * "retrying — last error was X" without entering an error state.
     */
    val failureReason: Throwable? = null,

    /** Epoch millis of the last successful load, or null if never loaded. */
    val dataUpdatedAt: Long? = null,

    /** Epoch millis of the last failure, or null. */
    val errorUpdatedAt: Long? = null,

    /** True when the data has been explicitly invalidated. */
    val isInvalidated: Boolean = false,

    /** True while showing `placeholderData` rather than real cached data. */
    val isPlaceholderData: Boolean = false,
) {
    public val isPending: Boolean get() = status == QueryStatus.Pending
    public val isError: Boolean get() = status == QueryStatus.Error
    public val isSuccess: Boolean get() = status == QueryStatus.Success

    /** True whenever a request is in flight, including background refetches. */
    public val isFetching: Boolean get() = fetchStatus == FetchStatus.Fetching

    /** True when the query wants to fetch but cannot — normally offline. */
    public val isPaused: Boolean get() = fetchStatus == FetchStatus.Paused

    /**
     * True only for a first-ever load that is actually in flight.
     *
     * **This is the flag that should drive a spinner**, not [isPending]. A
     * disabled or lazy query sits in [QueryStatus.Pending] indefinitely without
     * ever fetching, so `isPending` alone would show a spinner forever.
     */
    public val isLoading: Boolean get() = isPending && isFetching

    /** True when content is on screen and being refreshed underneath. */
    public val isRefreshing: Boolean get() = isSuccess && isFetching
}

/**
 * A lossy, exhaustive projection of [QueryState] for UI code that prefers a
 * sealed `when` over flag checks.
 *
 * Lossy on purpose: it cannot express every combination of the two axes. That
 * is acceptable at the UI boundary, where a screen has finitely many
 * renderings, and unacceptable in the core, which is why [QueryState] is not
 * modelled this way.
 */
public sealed interface QueryUiState<out T> {
    /** First load in flight, nothing to show yet. */
    public data object Loading : QueryUiState<Nothing>

    /** Data to render, possibly being refreshed. */
    public data class Content<T>(val data: T, val isRefreshing: Boolean) : QueryUiState<T>

    /** No data and the last attempt failed. */
    public data class Failed(val error: Throwable, val isRetrying: Boolean) : QueryUiState<Nothing>
}

/**
 * Project this state for UI rendering.
 *
 * Retained [QueryState.data] wins over an error, because showing stale content
 * with a refresh indicator beats blanking the screen on a transient failure.
 */
public fun <T> QueryState<T>.toUiState(): QueryUiState<T> {
    val currentData = data
    return when {
        currentData != null -> QueryUiState.Content(currentData, isRefreshing = isFetching)
        error != null -> QueryUiState.Failed(error, isRetrying = isFetching)
        else -> QueryUiState.Loading
    }
}
