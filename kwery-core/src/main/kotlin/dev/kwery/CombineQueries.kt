package dev.kwery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Combine several queries of **different types** into one typed state.
 *
 * [List.aggregate] handles many queries of the same type; this handles the
 * commoner screen-level case, where a screen needs a user *and* their settings
 * *and* their unread count, each a different type:
 *
 * ```kotlin
 * combineQueries(
 *     client.query(UserKey(id)) { api.user(id) },
 *     client.query(SettingsKey(id)) { api.settings(id) },
 * ) { user, settings -> ScreenState(user, settings) }
 * ```
 *
 * The alternative is `combine` plus a cast per query, which is what this
 * removes. Nothing here is possible without it — it is ergonomics, and the
 * casts it deletes are the kind that survive a refactor and fail at runtime.
 *
 * **The transform receives nullable data**, deliberately. A screen that has two
 * of its three pieces should be able to render what it has rather than blank,
 * which is the same reason [AggregateState.data] holds nullable slots. Check
 * [QueryState.isSuccess] on the result if you need everything present.
 *
 * Status follows the same rules as [aggregate]: `Error` if any errored with the
 * first error winning, `Success` only if all succeeded, `Fetching` outranking
 * `Paused` because something is in fact happening.
 */
public fun <A, B, R> combineQueries(
    a: Flow<QueryState<A>>,
    b: Flow<QueryState<B>>,
    transform: (A?, B?) -> R,
): Flow<QueryState<R>> = combine(a, b) { sa, sb ->
    merge(listOf(sa, sb), transform(sa.data, sb.data))
}

/** Three queries. See the two-argument [combineQueries]. */
public fun <A, B, C, R> combineQueries(
    a: Flow<QueryState<A>>,
    b: Flow<QueryState<B>>,
    c: Flow<QueryState<C>>,
    transform: (A?, B?, C?) -> R,
): Flow<QueryState<R>> = combine(a, b, c) { sa, sb, sc ->
    merge(listOf(sa, sb, sc), transform(sa.data, sb.data, sc.data))
}

/** Four queries. See the two-argument [combineQueries]. */
public fun <A, B, C, D, R> combineQueries(
    a: Flow<QueryState<A>>,
    b: Flow<QueryState<B>>,
    c: Flow<QueryState<C>>,
    d: Flow<QueryState<D>>,
    transform: (A?, B?, C?, D?) -> R,
): Flow<QueryState<R>> = combine(a, b, c, d) { sa, sb, sc, sd ->
    merge(listOf(sa, sb, sc, sd), transform(sa.data, sb.data, sc.data, sd.data))
}

/** Five queries. Beyond this, a screen is probably doing too much. */
public fun <A, B, C, D, E, R> combineQueries(
    a: Flow<QueryState<A>>,
    b: Flow<QueryState<B>>,
    c: Flow<QueryState<C>>,
    d: Flow<QueryState<D>>,
    e: Flow<QueryState<E>>,
    transform: (A?, B?, C?, D?, E?) -> R,
): Flow<QueryState<R>> = combine(a, b, c, d, e) { sa, sb, sc, sd, se ->
    merge(listOf(sa, sb, sc, sd, se), transform(sa.data, sb.data, sc.data, sd.data, se.data))
}

/**
 * Reduce the source states to one, carrying [data].
 *
 * Shared by every arity so the rules cannot drift between them — the whole
 * reason overloads like these are worth writing once rather than at each call
 * site.
 */
private fun <R> merge(states: List<QueryState<*>>, data: R): QueryState<R> {
    // skipDisabled = true, matching `aggregate()`. A disabled query never
    // resolves, so counting it as pending holds the whole screen in Pending for
    // ever — the trap that default exists to avoid, and it applies just as much
    // to a screen combining three types as to a list of one.
    val aggregate = states
        .map { QueryState<Unit>(status = it.status, fetchStatus = it.fetchStatus, error = it.error) }
        .aggregate(skipDisabled = true)

    return QueryState(
        data = data,
        status = aggregate.status,
        fetchStatus = aggregate.fetchStatus,
        error = aggregate.error,
        dataUpdatedAt = states.mapNotNull { it.dataUpdatedAt }.maxOrNull(),
    )
}
