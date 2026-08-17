package dev.kwery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * One cache entry: its state, its observers, and the single in-flight request
 * they share.
 *
 * Implements **approach C′** from `docs/roadmap/05-deduplication-observers.md`,
 * which was settled by measurement rather than reasoning. All mutation goes
 * through [mutex]; nothing here is safe to touch without it.
 */
/** Carries a fetch result across the `async` boundary without throwing. */
internal sealed interface FetchOutcome<out T> {
    @JvmInline value class Ok<T>(val value: T) : FetchOutcome<T>
    @JvmInline value class Failed(val error: Throwable) : FetchOutcome<Nothing>
}

internal class QueryEntry<T>(
    val key: QueryKey<T>,
    val options: QueryOptions,
    /**
     * Null for a **seeded** entry — one created by `setQueryData` with no
     * fetcher. Such an entry holds data but cannot refetch, until something
     * observes it with a real fetcher and the client replaces it.
     */
    private val fetcher: (suspend () -> T)?,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource,
    private val onlineManager: OnlineManager,
    private val gracePeriodMillis: Long,
    /** True while a persisted cache is being restored; suppresses fetching. */
    private val isRestoring: () -> Boolean,
    private val onFetchStarted: () -> Unit,
    private val onFetchSettled: () -> Unit,
    /**
     * Suspending, because removing an entry requires the client's map lock.
     * The only lock nesting in this class is `entry.mutex -> entriesMutex`, and
     * the client never takes them the other way round, so there is no inversion.
     */
    private val onEvict: suspend (QueryEntry<*>) -> Unit,
) {
    val state: MutableStateFlow<QueryState<T>> = MutableStateFlow(QueryState())

    val hasFetcher: Boolean get() = fetcher != null

    private val mutex = Mutex()

    // Read without the mutex by snapshot(); written only under it.
    @Volatile
    private var observers = 0
    private var inFlight: Deferred<FetchOutcome<T>>? = null
    private var graceJob: Job? = null
    private var gcJob: Job? = null

    /** Drives LRU eviction. Updated on every attach. */
    var lastAccessMillis: Long = timeSource.nowMillis()
        private set

    /**
     * When a reattach was last treated as a continuation rather than a mount.
     *
     * Focus-triggered refetches consult this too: backgrounding and returning
     * within the grace window produces BOTH a reattach and a focus event, and
     * suppressing only the reattach would let the focus event refetch anyway.
     */
    private var lastContinuationMillis: Long? = null

    val observerCount: Int get() = observers

    fun snapshot(): QueryEntrySnapshot {
        val current = state.value
        return QueryEntrySnapshot(
            key = key,
            status = current.status,
            fetchStatus = current.fetchStatus,
            dataUpdatedAt = current.dataUpdatedAt,
            // A disabled query is never stale — it has opted out of the whole
            // staleness mechanism, so reporting it stale would make it match
            // `stale = true` filters it can never satisfy.
            isStale = options.enabled && isStaleNow(),
            isInvalidated = current.isInvalidated,
            observerCount = observers,
        )
    }

    /**
     * Raise this entry's `gcTime` to [candidate] if it is longer.
     *
     * TanStack keeps the **longest** gcTime it has ever seen for a key rather
     * than the most recent, so a short-lived observer cannot shorten the
     * retention another caller asked for. Ported from
     * `should use the longest garbage collection time it has seen`.
     */
    fun raiseGcTime(candidate: Duration) {
        if (candidate > effectiveGcTime) effectiveGcTime = candidate
    }

    @Volatile
    private var effectiveGcTime: Duration = options.gcTime

    val gcTime: Duration get() = effectiveGcTime

    /**
     * Seed this entry before it has ever fetched.
     *
     * The caller ([QueryClient.obtain]) only invokes this for an entry that did
     * not previously exist, which is what stops a guess overwriting a real
     * response. Deliberately no second check here: a redundant guard reads as
     * load-bearing, and a reader cannot tell it never fires.
     */
    fun applyInitialData(initialData: InitialData<T>?, nowMillis: Long) {
        if (initialData == null) return
        val seeded = initialData.value() ?: return
        state.value = state.value.copy(
            data = seeded,
            status = QueryStatus.Success,
            // Honouring the caller's timestamp is what makes staleness correct:
            // data lifted from a list fetched five minutes ago is five minutes
            // old, not new, and should refetch accordingly.
            dataUpdatedAt = initialData.updatedAt ?: nowMillis,
        )
    }

    // ---- Observer lifecycle ---------------------------------------------

    suspend fun attach() = mutex.withLock {
        observers++
        lastAccessMillis = timeSource.nowMillis()

        // A reattach landing inside the grace window is a continuation of the
        // same logical mount — rotation, a brief navigation, an app switch.
        val withinGrace = graceJob?.isActive == true

        graceJob?.cancel()
        graceJob = null
        gcJob?.cancel()
        gcJob = null

        if (withinGrace) {
            lastContinuationMillis = timeSource.nowMillis()
            return@withLock
        }
        if (!options.enabled) return@withLock
        if (shouldRefetchFor(options.refetchOnMount)) startFetchLocked()
    }

    /**
     * Must be called with [kotlinx.coroutines.NonCancellable]: it runs while the
     * collecting coroutine is being cancelled, and a suspending lock acquisition
     * would otherwise throw before the bookkeeping happened, leaking the entry.
     */
    suspend fun detach() = mutex.withLock {
        observers--
        if (observers > 0) return@withLock

        graceJob = scope.launch {
            delay(gracePeriodMillis)
            mutex.withLock {
                // An observer may have attached while this coroutine was
                // waiting on the mutex; attach() cancelled us, but cancellation
                // is not instantaneous.
                if (observers > 0) return@withLock

                inFlight?.cancel()
                inFlight = null

                gcJob = scope.launch {
                    delay(effectiveGcTime.inWholeMilliseconds)
                    mutex.withLock { if (observers == 0) onEvict(this@QueryEntry) }
                }
            }
        }
    }

    // ---- Fetching --------------------------------------------------------

    private fun isStaleNow(): Boolean {
        val current = state.value
        return current.isInvalidated ||
            options.staleTime.isStale(current.dataUpdatedAt, timeSource.nowMillis())
    }

    /** Fetch if not already in flight. Caller must hold [mutex]. */
    private fun startFetchLocked() {
        val fetch = fetcher ?: return // seeded entry: nothing to call
        // Hold off while a restore is running: the data may be about to arrive
        // from disk, and fetching now would race it.
        if (isRestoring()) return
        if (inFlight != null) return // deduplicated: join the existing request

        state.value = state.value.copy(
            fetchStatus = FetchStatus.Fetching,
            failureCount = 0,
            failureReason = null,
        )

        onFetchStarted()
        val attempt: Deferred<FetchOutcome<T>> = scope.async {
            // The failure is caught INSIDE the async body and carried out as a
            // value. Letting it propagate would hand the user a
            // stacktrace-recovered COPY of their own exception instead of the
            // instance their query function actually threw.
            try {
                FetchOutcome.Ok(runWithRetry(fetch))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                FetchOutcome.Failed(error)
            }
        }
        inFlight = attempt

        scope.launch {
            try {
                val value = when (val outcome = attempt.await()) {
                    is FetchOutcome.Ok -> outcome.value
                    is FetchOutcome.Failed -> {
                        recordFailure(attempt, outcome.error)
                        onFetchSettled()
                        return@launch
                    }
                }
                mutex.withLock {
                    if (inFlight === attempt) inFlight = null
                    state.value = state.value.copy(
                        data = value,
                        error = null,
                        status = QueryStatus.Success,
                        fetchStatus = FetchStatus.Idle,
                        dataUpdatedAt = timeSource.nowMillis(),
                        isInvalidated = false,
                        failureCount = 0,
                        failureReason = null,
                    )
                }
                onFetchSettled()
            } catch (cancellation: CancellationException) {
                // Cancellation is not failure. The query reverts to its prior
                // state rather than entering an error state, so navigating away
                // never shows an error.
                mutex.withLock {
                    if (inFlight === attempt) inFlight = null
                    state.value = state.value.copy(
                        fetchStatus = FetchStatus.Idle,
                        failureCount = 0,
                        failureReason = null,
                    )
                }
                onFetchSettled()
            } catch (error: Throwable) {
                mutex.withLock {
                    if (inFlight === attempt) inFlight = null
                    state.value = state.value.copy(
                        error = error,
                        // data is deliberately retained: a failed background
                        // refetch must not blank a screen showing content.
                        status = QueryStatus.Error,
                        fetchStatus = FetchStatus.Idle,
                        errorUpdatedAt = timeSource.nowMillis(),
                    )
                }
                onFetchSettled()
            }
        }
    }

    /** Record a failure carrying the user's original throwable. */
    private suspend fun recordFailure(attempt: Deferred<FetchOutcome<T>>, error: Throwable) {
        mutex.withLock {
            if (inFlight === attempt) inFlight = null
            state.value = state.value.copy(
                error = error,
                // data is deliberately retained: a failed background refetch
                // must not blank a screen showing content.
                status = QueryStatus.Error,
                fetchStatus = FetchStatus.Idle,
                errorUpdatedAt = timeSource.nowMillis(),
            )
        }
    }

    private suspend fun runWithRetry(fetch: suspend () -> T): T {
        var failureCount = 0
        while (true) {
            awaitFetchAllowed()
            try {
                // A fetcher that swallows CancellationException in a broad
                // `catch (e: Exception)` cannot fabricate a success: it runs
                // inside a cancelled `async`, so `await()` throws whatever the
                // body returned. Containment is structural — an explicit
                // isActive check here was verified redundant by mutation
                // testing and removed rather than left looking load-bearing.
                return fetch()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (!options.retry.shouldRetry(failureCount, error)) throw error

                val wait = options.retryDelay.delayFor(failureCount, error)
                failureCount++
                mutex.withLock {
                    state.value = state.value.copy(
                        failureCount = failureCount,
                        failureReason = error,
                    )
                }
                delay(wait)
            }
        }
    }

    /**
     * Honour [NetworkMode] before each attempt.
     *
     * Under [NetworkMode.Online] a query with no connectivity reports
     * [FetchStatus.Paused] and waits. Resuming **continues** the retry
     * sequence rather than restarting it, so a query paused on attempt 2
     * resumes at attempt 3 with the correct backoff.
     */
    private suspend fun awaitFetchAllowed() {
        if (options.networkMode == NetworkMode.Always) return
        if (onlineManager.isOnline.value) return

        mutex.withLock {
            state.value = state.value.copy(fetchStatus = FetchStatus.Paused)
        }
        onlineManager.isOnline.first { it }
        mutex.withLock {
            state.value = state.value.copy(fetchStatus = FetchStatus.Fetching)
        }
    }

    /** True when [policy] permits a fetch right now. Caller must hold [mutex]. */
    private fun shouldRefetchFor(policy: RefetchOn): Boolean {
        // A query that has never loaded is doing an INITIAL fetch, not a
        // refetch. StaleTime.Static suppresses refetching, but a Static query
        // that never fetched would hold no data forever — TanStack likewise
        // reports a dataless Static query as stale.
        val neverLoaded = state.value.dataUpdatedAt == null && state.value.error == null
        if (neverLoaded) return true

        return when (policy) {
            RefetchOn.Never -> false
            // Static refuses every automatic refetch, including "Always".
            RefetchOn.Always -> options.staleTime.allowsAutomaticRefetch
            RefetchOn.IfStale -> options.staleTime.allowsAutomaticRefetch && isStaleNow()
        }
    }

    private fun withinContinuationWindow(): Boolean {
        val at = lastContinuationMillis ?: return false
        return !isElapsed(timeSource.nowMillis(), at, gracePeriodMillis)
    }

    /** The app returned to the foreground. */
    suspend fun onFocusRegained(): Unit = onEnvironmentTrigger(options.refetchOnFocus)

    /** Connectivity returned. */
    suspend fun onReconnected(): Unit = onEnvironmentTrigger(options.refetchOnReconnect)

    private suspend fun onEnvironmentTrigger(policy: RefetchOn) = mutex.withLock {
        if (!options.enabled) return@withLock
        // Only queries something is actually watching refetch on these triggers.
        if (observers == 0) return@withLock
        if (withinContinuationWindow()) return@withLock
        if (shouldRefetchFor(policy)) startFetchLocked()
    }

    // ---- External operations --------------------------------------------

    /**
     * Mark stale and, if observed, start a refetch.
     *
     * Returns the in-flight fetch so the caller can await it. That is what lets
     * `onSettled = { invalidateQueries(...) }` keep a mutation `Pending` until
     * the list has actually refreshed, rather than flashing "done" and then
     * visibly updating a moment later.
     */
    suspend fun invalidate(): Deferred<*>? = mutex.withLock {
        if (!options.staleTime.allowsInvalidation) return@withLock null
        // A disabled query ignores invalidation entirely, matching TanStack:
        // it is excluded even from refetchType 'all'.
        if (!options.enabled) return@withLock null
        // Idempotent: invalidating twice must not produce a second state object
        // or a second fetch.
        if (state.value.isInvalidated) return@withLock null

        state.value = state.value.copy(isInvalidated = true)
        if (observers == 0) return@withLock null
        startFetchLocked()
        inFlight
    }

    suspend fun refetch(): Deferred<*>? = mutex.withLock {
        if (!options.enabled) return@withLock null
        startFetchLocked()
        inFlight
    }

    suspend fun cancel() = mutex.withLock {
        inFlight?.cancel()
        inFlight = null
    }

    suspend fun setData(value: T?, updatedAt: Long) = mutex.withLock {
        state.value = state.value.copy(
            data = value,
            status = if (value != null) QueryStatus.Success else state.value.status,
            dataUpdatedAt = if (value != null) updatedAt else state.value.dataUpdatedAt,
            isInvalidated = false,
        )
    }

    suspend fun markOptimistic(optimistic: Boolean) = mutex.withLock {
        state.value = state.value.copy(isOptimistic = optimistic)
    }

    suspend fun reset() = mutex.withLock {
        inFlight?.cancel()
        inFlight = null
        state.value = QueryState()
    }

    /** Cancel every timer this entry owns. Called when it leaves the cache. */
    fun dispose() {
        graceJob?.cancel()
        gcJob?.cancel()
        inFlight?.cancel()
    }
}
