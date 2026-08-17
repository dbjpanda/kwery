package dev.kwery

/** Which queries a filter considers, by observer state. */
public enum class QueryType {
    /** Queries with at least one observer. */
    Active,

    /** Queries with no observers, still cached. */
    Inactive,

    All,
}

/**
 * An immutable view of a cache entry, passed to [QueryFilters.predicate].
 *
 * Deliberately a snapshot rather than the live entry: TanStack hands predicates
 * the mutable `Query` object, which invites predicates that mutate cache state
 * as a side effect of being asked a question.
 */
public data class QueryEntrySnapshot(
    val key: QueryKey<*>,
    val status: QueryStatus,
    val fetchStatus: FetchStatus,
    val dataUpdatedAt: Long?,
    val isStale: Boolean,
    val isInvalidated: Boolean,
    val observerCount: Int,
) {
    public val isActive: Boolean get() = observerCount > 0
}

/**
 * Selects a set of cached queries, for invalidation, refetching, removal,
 * cancellation and reset.
 *
 * There is **no no-argument default**. In TanStack, `invalidateQueries()`
 * invalidates the entire cache while reading like "the thing I just changed" —
 * a footgun whose cost is invisible in development and expensive on cellular.
 * Invalidating everything must be spelled out:
 *
 * ```kotlin
 * client.invalidateQueries(TodoKey("5"))        // exact
 * client.invalidateQueries(prefixOf("todos"))   // partial match
 * client.invalidateQueries(QueryFilters.All)    // everything, and it says so
 * ```
 */
public data class QueryFilters(
    /**
     * Partially match keys against these parts. See [partialMatchKey] — this is
     * a deep partial match, not merely a list prefix, so a filter of
     * `["todos", {"done": true}]` matches an entry keyed
     * `["todos", {"done": true, "page": 1}]`.
     */
    val keyPrefix: List<Any?>? = null,

    /** Match exactly one entry, by key equality. */
    val exactKey: QueryKey<*>? = null,

    val type: QueryType = QueryType.All,

    /** Match only stale (`true`) or only fresh (`false`) queries. */
    val stale: Boolean? = null,

    val fetchStatus: FetchStatus? = null,

    /** Applied last, after all other criteria. */
    val predicate: ((QueryEntrySnapshot) -> Boolean)? = null,
) {
    /** True when [snapshot] satisfies every criterion set on this filter. */
    public fun matches(snapshot: QueryEntrySnapshot): Boolean {
        exactKey?.let { if (snapshot.key != it) return false }
        keyPrefix?.let { if (!partialMatchKey(snapshot.key.parts, it)) return false }

        when (type) {
            QueryType.Active -> if (!snapshot.isActive) return false
            QueryType.Inactive -> if (snapshot.isActive) return false
            QueryType.All -> Unit
        }

        stale?.let { if (snapshot.isStale != it) return false }
        fetchStatus?.let { if (snapshot.fetchStatus != it) return false }
        predicate?.let { if (!it(snapshot)) return false }

        return true
    }

    public companion object {
        /**
         * Matches every cached query. Required explicitly for cache-wide
         * operations, so they can never happen by accident.
         */
        public val All: QueryFilters = QueryFilters()
    }
}
