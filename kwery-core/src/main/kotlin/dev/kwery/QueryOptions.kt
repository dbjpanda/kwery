package dev.kwery

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Per-query configuration.
 *
 * Not generic: nothing here depends on the query's data type, which lets the
 * same object serve as a client-wide default.
 */
public data class QueryOptions(
    /**
     * How long loaded data stays fresh. Defaults to [StaleTime.Zero] — stale
     * immediately — matching TanStack.
     */
    val staleTime: StaleTime = StaleTime.Zero,

    /**
     * How long an entry with no observers stays in memory before eviction.
     *
     * Note this is a *different clock* from [staleTime]: one governs
     * refetching, the other eviction. The grace window
     * ([QueryClientConfig.gracePeriod]) runs first, so the total delay from the
     * last observer leaving to eviction is `gracePeriod + gcTime`.
     */
    val gcTime: Duration = 5.minutes,

    val retry: RetryPolicy = RetryPolicy.Default,

    val retryDelay: RetryDelay = RetryDelay.Default,

    /**
     * When false the query never fetches automatically and ignores
     * invalidation. It holds whatever data is already cached.
     */
    val enabled: Boolean = true,

    val networkMode: NetworkMode = NetworkMode.Online,
)

/** Client-wide configuration. */
public data class QueryClientConfig(
    val timeSource: TimeSource = TimeSource.System,

    val focusManager: FocusManager = FocusManager.AlwaysFocused,

    val onlineManager: OnlineManager = OnlineManager.AlwaysOnline,

    /**
     * How long after the last observer leaves before an entry is treated as
     * inactive.
     *
     * This window does two jobs, both established by measurement (see
     * `docs/roadmap/05-deduplication-observers.md`):
     *
     * 1. It defers eviction, absorbing rotation and navigation churn.
     * 2. **A reattach inside the window is a continuation, not a fresh mount**,
     *    so it skips the refetch-on-mount staleness check. Without this,
     *    rotation with the default `staleTime = 0` fires a redundant request
     *    every time.
     *
     * Defaults to 5 seconds, matching the `SharingStarted.WhileSubscribed`
     * value Android developers already use.
     */
    val gracePeriod: Duration = 5.seconds,

    /**
     * Upper bound on cached entries. Least-recently-used **inactive** entries
     * are evicted above this; an observed entry is never evicted, whatever the
     * pressure.
     *
     * `gcTime` bounds the cache by time and nothing bounds it by size. A
     * browser tab gets reloaded; an Android process can live for days, so an
     * unbounded cache of large responses is a real risk TanStack never had to
     * solve.
     */
    val maxEntries: Int = 500,

    /** Defaults applied to queries that do not override them. */
    val defaultQueryOptions: QueryOptions = QueryOptions(),
) {
    init {
        require(maxEntries > 0) { "maxEntries must be > 0, was $maxEntries" }
    }
}
