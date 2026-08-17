package dev.kwery

import kotlin.time.Duration

/**
 * How long loaded data is considered **fresh**.
 *
 * This governs *refetching*. It is a different clock from `gcTime`, which
 * governs *eviction* of unobserved entries — conflating the two is the most
 * common misunderstanding of this kind of library.
 *
 * The default is [Zero]: data is stale the moment it arrives, so any new
 * observer, app foreground or reconnect triggers a background refresh. That
 * matches TanStack Query.
 *
 * [Infinite] and [Static] both stop staleness-driven refetching, and the
 * difference between them is deliberate:
 *
 * | | staleness refetch | manual invalidation |
 * |---|---|---|
 * | [Infinite] | no | **yes** |
 * | [Static] | no | **no** |
 *
 * Use [Static] only for data that genuinely cannot change while the app runs —
 * feature flags read at boot, permissions loaded at login, static reference
 * tables.
 *
 * Modelled as a sealed interface rather than a duration with sentinel values,
 * so `when` is exhaustive and no magic number can leak into user code. The
 * allocation is irrelevant: a `StaleTime` is built once per query options
 * object, not per cache lookup.
 */
public sealed interface StaleTime {

    /** Fresh until [duration] has passed since the data loaded. */
    public data class After(val duration: Duration) : StaleTime

    /** Never stale by time, but still yields to explicit invalidation. */
    public data object Infinite : StaleTime

    /** Never refetches at all. Invalidation has no effect. */
    public data object Static : StaleTime

    public companion object {
        /** Stale immediately — the default, matching TanStack Query. */
        public val Zero: StaleTime = After(Duration.ZERO)

        public fun of(duration: Duration): StaleTime = After(duration)
    }
}

/**
 * Whether data loaded at [dataUpdatedAt] is stale at [nowMillis].
 *
 * Data that has never loaded is always stale.
 */
public fun StaleTime.isStale(dataUpdatedAt: Long?, nowMillis: Long): Boolean {
    if (dataUpdatedAt == null) return true
    return when (this) {
        is StaleTime.After -> isElapsed(nowMillis, dataUpdatedAt, duration.inWholeMilliseconds)
        StaleTime.Infinite, StaleTime.Static -> false
    }
}

/**
 * Whether `invalidateQueries` can mark this query stale.
 *
 * False only for [StaleTime.Static], which is what separates it from
 * [StaleTime.Infinite].
 */
public val StaleTime.allowsInvalidation: Boolean
    get() = this != StaleTime.Static

/**
 * Whether automatic refetch triggers apply — including those configured as
 * `"always"`, which [StaleTime.Static] blocks.
 */
public val StaleTime.allowsAutomaticRefetch: Boolean
    get() = this != StaleTime.Static
