package dev.kwery

/**
 * Why a fetch started.
 *
 * The reason has to be recorded **at the moment of the transition**. "This
 * refetched because the app came back and its `staleTime` had elapsed" cannot be
 * reconstructed afterwards from state alone, and it is the question every user
 * of a cache eventually asks.
 */
public enum class FetchReason {
    /** A screen started observing a key that was missing or stale. */
    Mount,

    /** `invalidateQueries` marked it stale and something was watching. */
    Invalidated,

    /** `refetchQueries`, or an explicit refetch on the query itself. */
    Manual,

    /** The app returned to the foreground. */
    FocusRegained,

    /** Connectivity came back. */
    Reconnected,

    /** A `refetchInterval` tick. */
    Interval,

    /** `prefetchQuery`, `fetchQuery` or `ensureQueryData`, with nothing observing. */
    Prefetch,
}

/** Why an entry left the cache. */
public enum class EvictReason {
    /** `gcTime` elapsed with nothing observing it. */
    GarbageCollected,

    /** Dropped to stay under [QueryClientConfig.maxEntries]. */
    OverCapacity,

    /** `removeQueries` asked for it directly. */
    Removed,
}

/**
 * Something that happened to one cache entry.
 *
 * Consumed through [QueryClient.events]. This is the surface devtools are built
 * on, and it exists in the core from the start for a reason: a cache whose
 * transitions were never observable cannot be given devtools later without
 * breaking changes.
 */
public sealed interface QueryEvent {
    /** The entry this happened to. */
    public val key: QueryKey<*>

    /** When, on the client's [TimeSource]. */
    public val atMillis: Long

    public data class FetchStarted(
        override val key: QueryKey<*>,
        override val atMillis: Long,
        val reason: FetchReason,
    ) : QueryEvent

    public data class FetchSucceeded(
        override val key: QueryKey<*>,
        override val atMillis: Long,
        /** How long the fetch took, including retries. */
        val durationMillis: Long,
    ) : QueryEvent

    public data class FetchFailed(
        override val key: QueryKey<*>,
        override val atMillis: Long,
        val error: Throwable,
        /** Attempts made, so a retry storm is visible rather than inferred. */
        val attempts: Int,
    ) : QueryEvent

    /** Waiting for connectivity rather than failing. */
    public data class Paused(
        override val key: QueryKey<*>,
        override val atMillis: Long,
    ) : QueryEvent

    public data class Resumed(
        override val key: QueryKey<*>,
        override val atMillis: Long,
    ) : QueryEvent

    public data class Invalidated(
        override val key: QueryKey<*>,
        override val atMillis: Long,
        /** False when nothing was observing, so it went stale without fetching. */
        val refetching: Boolean,
    ) : QueryEvent

    /** Data written directly, by a manual write or a hydration. */
    public data class DataSet(
        override val key: QueryKey<*>,
        override val atMillis: Long,
    ) : QueryEvent

    public data class ObserverAttached(
        override val key: QueryKey<*>,
        override val atMillis: Long,
        val observerCount: Int,
    ) : QueryEvent

    public data class ObserverDetached(
        override val key: QueryKey<*>,
        override val atMillis: Long,
        val observerCount: Int,
    ) : QueryEvent

    public data class Evicted(
        override val key: QueryKey<*>,
        override val atMillis: Long,
        val reason: EvictReason,
    ) : QueryEvent
}
