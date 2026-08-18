package dev.kwery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the query cache.
 *
 * One instance per application is the intended usage; two clients hold entirely
 * separate caches and share nothing.
 *
 * ```kotlin
 * val client = QueryClient(scope = applicationScope)
 *
 * client.query(TodoKey(id)) { api.todo(id) }        // Flow<QueryState<Todo>>
 *     .stateIn(viewModelScope, WhileSubscribed(5_000), QueryState())
 * ```
 */
public class QueryClient(
    scope: CoroutineScope? = null,
    public val config: QueryClientConfig = QueryClientConfig(),
) {
    /**
     * All cache work runs here.
     *
     * Wrapped in a [SupervisorJob] even when a scope is supplied: a query
     * function that throws would otherwise fail its `async`, cancel the parent
     * job, and take **every other query and the whole client** down with it.
     * Failures must stay confined to the query that produced them.
     */
    private val scope: CoroutineScope = (scope ?: CoroutineScope(SupervisorJob())).let { parent ->
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }

    private val entries = LinkedHashMap<QueryKey<*>, QueryEntry<*>>()
    private val entriesMutex = Mutex()

    init {
        // Rising edges only: `drop(1)` skips the StateFlow's current value, so
        // constructing a client while already focused and online is not itself
        // a trigger.
        this.scope.launch {
            config.focusManager.isFocused.observeReturns { entry -> entry.onFocusRegained() }
        }
        this.scope.launch {
            config.onlineManager.isOnline.observeReturns { entry -> entry.onReconnected() }
        }
    }

    // ---- Observing -------------------------------------------------------

    /**
     * Observe [key], fetching with [fetcher] when the data is stale.
     *
     * The returned [Flow] is cold. Collecting it attaches an observer;
     * cancelling the collecting coroutine detaches it. Multiple collectors of
     * the same key share one cache entry and one in-flight request.
     *
     * Detaching does not immediately evict: a grace window
     * ([QueryClientConfig.gracePeriod]) runs first, and reattaching inside it
     * counts as the same mount — so rotation neither refetches nor evicts.
     */
    public fun <T> query(
        key: QueryKey<T>,
        options: QueryOptions = config.defaultQueryOptions,
        initialData: InitialData<T>? = null,
        fetcher: suspend () -> T,
    ): Flow<QueryState<T>> = flow {
        val entry = obtain(key, options, fetcher, initialData)
        entry.attach()
        try {
            emitAll(entry.state)
        } finally {
            // Runs while this coroutine is being cancelled; without
            // NonCancellable the lock acquisition throws and the observer count
            // is never decremented, leaking the entry forever.
            withContext(NonCancellable) { entry.detach() }
        }
    }

    /**
     * Observe a projection of [key]'s data.
     *
     * Emissions are deduplicated, so a selector narrowing to a count only emits
     * when the count changes.
     */
    public fun <T, R> query(
        key: QueryKey<T>,
        options: QueryOptions = config.defaultQueryOptions,
        select: (T?) -> R,
        fetcher: suspend () -> T,
    ): Flow<R> = query(key, options, initialData = null, fetcher = fetcher)
        .map { select(it.data) }
        .distinctUntilChanged()

    // ---- Mutations -------------------------------------------------------

    private val optimisticRegistry = OptimisticRegistry()

    /**
     * Register an optimistic write and apply it to the cache.
     *
     * Cancels in-flight fetches first: a refetch resolving after the write
     * would overwrite it with stale server data.
     */
    @Suppress("UNCHECKED_CAST")
    internal suspend fun <V, T> beginOptimistic(
        key: QueryKey<T>,
        variables: V,
        apply: (T?, V) -> T?,
    ): Long {
        cancelQueries(QueryFilters(exactKey = key))
        val id = optimisticRegistry.nextId()
        val transform: (Any?) -> Any? = { current -> apply(current as T?, variables) }
        val next = optimisticRegistry.begin(key, id, getQueryData(key), transform)
        entryFor(key)?.markOptimistic(true)
        setQueryData(key) { next as T? }
        return id
    }

    /** Drop an optimistic write, re-deriving the cached value from what remains. */
    @Suppress("UNCHECKED_CAST")
    internal suspend fun <T> endOptimistic(
        key: QueryKey<T>,
        id: Long,
        committed: Boolean,
        invalidate: Boolean,
    ) {
        val (value, wasLast) = optimisticRegistry.end(key, id, committed)
        setQueryData(key) { value as T? }
        if (wasLast) {
            entryFor(key)?.markOptimistic(false)
            // Invalidate only once the LAST in-flight optimistic write clears.
            // Invalidating earlier would refetch server truth while another
            // optimistic write is still pending, clobbering it.
            if (invalidate) invalidateQueries(QueryFilters(exactKey = key))
        }
    }

    /** True while any optimistic write against [key] is in flight. */
    public suspend fun isOptimistic(key: QueryKey<*>): Boolean =
        optimisticRegistry.isOptimistic(key)

    /**
     * One lock per [MutationScope.id]; mutations sharing a scope share a lock.
     *
     * Held **weakly**. A strong map would grow for the life of the process with
     * every distinct scope id ever used, and per-entity scopes — `"todo-$id"`,
     * which is a natural way to serialise edits to one item — make that
     * unbounded. An Android process lives for days; a browser tab gets
     * reloaded. This is the same argument that put a `maxEntries` bound on the
     * query cache.
     *
     * Weak values are not merely a size trick, they are the correct lifetime:
     * the lock matters exactly while some [Mutation] holding it is alive. A
     * mutation mid-flight is strongly reachable from its own running coroutine,
     * so a lock can never be collected out from under one.
     */
    private val mutationLocks = mutableMapOf<String, java.lang.ref.WeakReference<Mutex>>()
    private val mutationLocksGuard = Mutex()

    /** Live entries in [mutationLocks]. For tests asserting it does not grow. */
    internal suspend fun mutationLockCount(): Int = mutationLocksGuard.withLock {
        mutationLocks.count { it.value.get() != null }
    }

    /**
     * Create a mutation.
     *
     * Mutations are not cached by key the way queries are — each call returns a
     * new [Mutation]. What *is* shared is the [MutationScope] lock, so two
     * mutations declaring the same scope serialise against each other even
     * though they are separate objects.
     */
    public suspend fun <V, R, C> mutation(options: MutationOptions<V, R, C>): Mutation<V, R> {
        val lock = options.scope?.let { declared ->
            mutationLocksGuard.withLock {
                mutationLocks[declared.id]?.get()
                    ?: Mutex().also { fresh ->
                        // Drop keys whose lock has been collected. Done on
                        // insert rather than on a timer: it only ever runs when
                        // the map is about to grow, and never on the hot path
                        // of an existing scope.
                        mutationLocks.values.removeAll { it.get() == null }
                        mutationLocks[declared.id] = java.lang.ref.WeakReference(fresh)
                    }
            }
        }
        return Mutation(
            options = options,
            coroutineScope = scope,
            timeSource = config.timeSource,
            onlineManager = config.onlineManager,
            serialLock = lock,
            onMutationStarted = ::onMutationStarted,
            onMutationSettled = ::onMutationSettled,
        )
    }

    // ---- Cache access ----------------------------------------------------

    /** The currently cached data for [key], without triggering a fetch. */
    public suspend fun <T> getQueryData(key: QueryKey<T>): T? = entryFor(key)?.state?.value?.data

    /** The full cached state for [key], or null if there is no entry. */
    public suspend fun <T> getQueryState(key: QueryKey<T>): QueryState<T>? =
        entryFor(key)?.state?.value

    /**
     * Write [data] into the cache without fetching.
     *
     * Creates the entry if it does not exist, so a detail view can be seeded
     * from a list that was already loaded. Such an entry has no fetcher and
     * cannot refetch until something observes it with one, at which point it
     * adopts that fetcher.
     */
    public suspend fun <T> setQueryData(key: QueryKey<T>, data: T) {
        val entry = obtainSeeded(key)
        entry.setData(data, config.timeSource.nowMillis())
    }

    /** Update cached data through [updater], which receives the current value. */
    public suspend fun <T> setQueryData(key: QueryKey<T>, updater: (T?) -> T?) {
        val entry = entryFor(key) ?: obtainSeeded(key)
        entry.setData(updater(entry.state.value.data), config.timeSource.nowMillis())
    }

    /**
     * Update cached data only if an entry already exists.
     *
     * Removes the nullable receiver from the common optimistic-update path,
     * where `it!!` inside an updater is a crash waiting for a refactor.
     */
    public suspend fun <T> updateQueryData(key: QueryKey<T>, update: (T) -> T) {
        val entry = entryFor(key) ?: return
        val current = entry.state.value.data ?: return
        entry.setData(update(current), config.timeSource.nowMillis())
    }

    // ---- Bulk operations -------------------------------------------------

    /**
     * Mark matching queries stale and refetch the active ones.
     *
     * There is no no-argument overload: invalidating the whole cache requires
     * [QueryFilters.All] explicitly, so it can never happen by accident.
     */
    public suspend fun invalidateQueries(filters: QueryFilters) {
        awaitAll(matching(filters).mapNotNull { it.invalidate() })
    }

    /** Invalidate exactly one entry. */
    public suspend fun invalidateQueries(key: QueryKey<*>): Unit =
        invalidateQueries(QueryFilters(exactKey = key))

    /** Invalidate every entry whose key partially matches [prefix]. */
    public suspend fun invalidateQueries(vararg prefix: Any?): Unit =
        invalidateQueries(QueryFilters(keyPrefix = prefix.toList()))

    /** Refetch matching queries regardless of staleness. */
    public suspend fun refetchQueries(filters: QueryFilters) {
        awaitAll(matching(filters).mapNotNull { it.refetch() })
    }

    /**
     * Wait for refetches to settle, without letting a failed one escape.
     *
     * A failing refetch is already reflected in that query's own state; letting
     * it propagate out of `invalidateQueries` would surface someone else's
     * failure at an unrelated call site.
     */
    private suspend fun awaitAll(pending: List<kotlinx.coroutines.Deferred<*>>) {
        pending.forEach { runCatching { it.await() } }
    }

    /** Cancel in-flight requests for matching queries, reverting their state. */
    public suspend fun cancelQueries(filters: QueryFilters) {
        matching(filters).forEach { it.cancel() }
    }

    /** Remove matching entries outright. No refetch, no data retained. */
    public suspend fun removeQueries(filters: QueryFilters) {
        val doomed = matching(filters)
        entriesMutex.withLock {
            doomed.forEach {
                entries.remove(it.key)
                it.dispose()
            }
        }
    }

    /** Reset matching entries to their initial state. */
    public suspend fun resetQueries(filters: QueryFilters) {
        matching(filters).forEach { it.reset() }
    }

    // ---- Hydration -------------------------------------------------------

    internal suspend fun dehydrateInternal(): List<DehydratedEntry> {
        val optimisticKeys = entriesMutex.withLock { entries.keys.toList() }
            .filter { optimisticRegistry.isOptimistic(it) }
            .toSet()

        return entriesMutex.withLock { entries.values.toList() }
            .mapNotNull { entry ->
                val current = entry.state.value
                val data = current.data ?: return@mapNotNull null
                val updatedAt = current.dataUpdatedAt ?: return@mapNotNull null
                // Never persist an unconfirmed write: it would come back on the
                // next launch looking like server truth.
                if (entry.key in optimisticKeys) return@mapNotNull null
                DehydratedEntry(entry.key, data, updatedAt)
            }
    }

    @Suppress("UNCHECKED_CAST")
    internal suspend fun hydrateInternal(restored: List<DehydratedEntry>) {
        for (entry in restored) {
            obtainSeeded(entry.key as QueryKey<Any?>).setData(entry.data, entry.dataUpdatedAt)
        }
    }

    /**
     * True while a persisted cache is being restored.
     *
     * Queries created during restoration hold in [FetchStatus.Idle] rather than
     * fetching, so a cold start does not race the restore and issue a request
     * for data that is about to arrive from disk.
     */
    public val isRestoring: StateFlow<Boolean> get() = restoringState.asStateFlow()

    private val restoringState = MutableStateFlow(false)

    /** Suspend until any in-progress restore has finished. */
    public suspend fun awaitRestored() {
        restoringState.first { !it }
    }

    /** Run [block] with [isRestoring] held true. Used by `kwery-persist`. */
    public suspend fun <T> withRestoring(block: suspend () -> T): T {
        restoringState.value = true
        return try {
            block()
        } finally {
            restoringState.value = false
        }
    }

    private val fetchingCount = MutableStateFlow(0)

    /**
     * How many queries are fetching right now, across the whole cache.
     *
     * For a global activity indicator. Counted rather than derived from a
     * snapshot, so it updates as fetches start and settle instead of only when
     * something asks.
     */
    public val isFetching: StateFlow<Int> get() = fetchingCount.asStateFlow()

    internal fun onFetchStarted() {
        fetchingCount.value += 1
    }

    internal fun onFetchSettled() {
        fetchingCount.value = (fetchingCount.value - 1).coerceAtLeast(0)
    }

    private val mutatingCount = MutableStateFlow(0)

    /**
     * How many mutations are running right now, across the whole client.
     *
     * The counterpart to [isFetching], and the signal behind a global "saving…"
     * indicator or a guard against navigating away mid-write.
     *
     * A mutation counts from the moment it is submitted until its callbacks
     * have finished — **including time spent queued** behind another mutation
     * in the same [MutationScope]. A queued write has not happened yet but is
     * going to, so treating it as idle would let a button re-enable between two
     * serialised submissions.
     */
    public val isMutating: StateFlow<Int> get() = mutatingCount.asStateFlow()

    internal fun onMutationStarted() {
        mutatingCount.value += 1
    }

    internal fun onMutationSettled() {
        mutatingCount.value = (mutatingCount.value - 1).coerceAtLeast(0)
    }

    /** Snapshots of every cached entry, for inspection and devtools. */
    public suspend fun cacheSnapshot(): List<QueryEntrySnapshot> =
        entriesMutex.withLock { entries.values.map { it.snapshot() } }

    /** Release every entry and stop all timers. */
    public fun close() {
        scope.cancel()
    }

    /**
     * Get or create an entry for a fetch that has **no observer**.
     *
     * Such an entry is inactive the moment it is created, so its `gcTime` timer
     * starts immediately. Prefetching far ahead of use can therefore be
     * collected before the user arrives — prefetch close to the point of use,
     * or raise `gcTime` for those keys.
     */
    internal suspend fun <T> obtainForFetch(
        key: QueryKey<T>,
        options: QueryOptions,
        fetcher: suspend () -> T,
    ): QueryEntry<T> = obtain(key, options, fetcher)

    // ---- Internals -------------------------------------------------------

    /**
     * Invoke [onReturn] for every entry when this flow returns to `true`, but
     * only if it was away for at least [QueryClientConfig.gracePeriod].
     *
     * A two-second app switch — a notification, replying to a message, the app
     * switcher — is not a return to the app, and a 200 ms connectivity blip is
     * not a reconnection. Treating them as triggers refetches every visible
     * query, repeatedly, on cellular. Reusing the grace window rather than
     * adding a separate throttle keeps this one concept instead of two.
     */
    private suspend fun kotlinx.coroutines.flow.Flow<Boolean>.observeReturns(
        onReturn: suspend (QueryEntry<*>) -> Unit,
    ) {
        var awaySince: Long? = null
        // drop(1) skips the StateFlow's current value: constructing a client
        // while already focused and online is not itself a trigger.
        drop(1).collect { present ->
            if (!present) {
                awaySince = config.timeSource.nowMillis()
                return@collect
            }
            val since = awaySince ?: return@collect
            awaySince = null
            val awayLongEnough = isElapsed(
                nowMillis = config.timeSource.nowMillis(),
                sinceMillis = since,
                durationMillis = config.gracePeriod.inWholeMilliseconds,
            )
            if (!awayLongEnough) return@collect
            entriesMutex.withLock { entries.values.toList() }.forEach { onReturn(it) }
        }
    }

    private suspend fun matching(filters: QueryFilters): List<QueryEntry<*>> =
        entriesMutex.withLock {
            entries.values.filter { filters.matches(it.snapshot()) }
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> entryFor(key: QueryKey<T>): QueryEntry<T>? =
        entriesMutex.withLock { entries[key] as QueryEntry<T>? }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> obtain(
        key: QueryKey<T>,
        options: QueryOptions,
        fetcher: suspend () -> T,
        initialData: InitialData<T>? = null,
    ): QueryEntry<T> = entriesMutex.withLock {
        val existing = entries[key] as QueryEntry<T>?
        if (existing != null && existing.hasFetcher) {
            // Longest gcTime ever seen for a key wins, so a short-lived
            // observer cannot shorten retention another caller asked for.
            existing.raiseGcTime(options.gcTime)
            return@withLock existing
        }

        // Either brand new, or a seeded entry adopting a real fetcher.
        val adopted = createEntry(key, options, fetcher, seedFrom = existing)
        // Only a brand-new entry is seeded. An entry that already exists holds
        // either a real response or another observer's seed, and replacing it
        // with a guess would be a silent regression.
        if (existing == null) adopted.applyInitialData(initialData, config.timeSource.nowMillis())
        entries[key] = adopted
        existing?.dispose()
        evictOverflowLocked()
        adopted
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> obtainSeeded(key: QueryKey<T>): QueryEntry<T> =
        entriesMutex.withLock {
            (entries[key] as QueryEntry<T>?) ?: createEntry(
                key = key,
                options = config.defaultQueryOptions,
                fetcher = null,
                seedFrom = null,
            ).also {
                entries[key] = it
                evictOverflowLocked()
            }
        }

    private fun <T> createEntry(
        key: QueryKey<T>,
        options: QueryOptions,
        fetcher: (suspend () -> T)?,
        seedFrom: QueryEntry<T>?,
    ): QueryEntry<T> {
        val entry = QueryEntry(
            key = key,
            options = options,
            fetcher = fetcher,
            scope = scope,
            timeSource = config.timeSource,
            onlineManager = config.onlineManager,
            gracePeriodMillis = config.gracePeriod.inWholeMilliseconds,
            isRestoring = { restoringState.value },
            onFetchStarted = ::onFetchStarted,
            onFetchSettled = ::onFetchSettled,
            isFocused = { config.focusManager.isFocused.value },
            onEvict = { evicted ->
                entriesMutex.withLock {
                    // Only remove if this is still the live entry for that key;
                    // it may have been replaced by an adopting entry.
                    if (entries[evicted.key] === evicted) entries.remove(evicted.key)
                }
                evicted.dispose()
            },
        )
        if (seedFrom != null) {
            entry.state.value = seedFrom.state.value
            entry.raiseGcTime(seedFrom.gcTime)
        }
        return entry
    }

    /**
     * Enforce [QueryClientConfig.maxEntries] by dropping least-recently-used
     * **inactive** entries. An observed entry is never evicted this way —
     * discarding data that is on screen is worse than using the memory.
     */
    private fun evictOverflowLocked() {
        val evictable = entries.values
            .filter { it.observerCount == 0 }
            .sortedBy { it.lastAccessMillis }
        var overflow = entries.size - config.maxEntries
        for (entry in evictable) {
            if (overflow <= 0) break
            entries.remove(entry.key)
            entry.dispose()
            overflow--
        }
    }
}
