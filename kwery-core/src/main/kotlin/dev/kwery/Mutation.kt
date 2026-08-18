package dev.kwery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A mutation's lifecycle state. Unlike a query, it has an [Idle] state: it has not run yet. */
public enum class MutationStatus { Idle, Pending, Error, Success }

/**
 * Serialises mutations that must not run concurrently.
 *
 * Mutations with the same [id] run one at a time, in the order they were
 * invoked. A mutation waiting its turn reports [MutationState.isPaused].
 *
 * Use it where concurrent writes would race — reordering a list, incrementing
 * a counter, appending to a document.
 */
public data class MutationScope(val id: String)

/**
 * Observable state of a mutation.
 *
 * @param V the variables type, @param R the result type.
 */
public data class MutationState<V, R>(
    val status: MutationStatus = MutationStatus.Idle,
    val data: R? = null,
    val error: Throwable? = null,

    /**
     * The variables of the most recent invocation.
     *
     * **Retained after an error**, so a failed row can offer a retry button
     * without the caller having to stash the input itself.
     */
    val variables: V? = null,

    /** Epoch millis of the most recent invocation. Distinguishes concurrent runs. */
    val submittedAt: Long? = null,

    /** Waiting: for its [MutationScope] turn, or for connectivity. */
    val isPaused: Boolean = false,

    val failureCount: Int = 0,
) {
    public val isIdle: Boolean get() = status == MutationStatus.Idle
    public val isPending: Boolean get() = status == MutationStatus.Pending
    public val isError: Boolean get() = status == MutationStatus.Error
    public val isSuccess: Boolean get() = status == MutationStatus.Success
}

/**
 * Configuration for a mutation.
 *
 * [C] is the type returned by [onMutate] and handed back to [onError] and
 * [onSettled] — the rollback channel. TanStack types it `unknown` and every
 * consumer casts; here it is a real type parameter, so a rollback snapshot
 * cannot be misread. Since rollback correctness is exactly where optimistic
 * updates go wrong, the extra parameter earns its place.
 *
 * `C` appears only here, not on [Mutation], because nothing on a mutation's
 * observable surface exposes it.
 */
public class MutationOptions<V, R, C>(
    public val mutationFn: suspend (V) -> R,

    /** Runs before the mutation. Its return value reaches [onError] and [onSettled]. */
    public val onMutate: (suspend (variables: V) -> C)? = null,

    public val onSuccess: (suspend (data: R, variables: V, context: C?) -> Unit)? = null,

    public val onError: (suspend (error: Throwable, variables: V, context: C?) -> Unit)? = null,

    public val onSettled: (
        suspend (data: R?, error: Throwable?, variables: V, context: C?) -> Unit
    )? = null,

    /**
     * Defaults to [RetryPolicy.ForMutations] — **no retries**, unlike queries.
     *
     * A retried non-idempotent write can charge a customer twice. Opt in only
     * for writes that are safe to repeat.
     */
    public val retry: RetryPolicy = RetryPolicy.ForMutations,

    public val retryDelay: RetryDelay = RetryDelay.Default,

    /** Serialise against other mutations sharing this scope. */
    public val scope: MutationScope? = null,

    public val networkMode: NetworkMode = NetworkMode.Online,
)

/**
 * A write.
 *
 * ```kotlin
 * val addTodo = client.mutation(
 *     MutationOptions<String, Todo, Unit>(
 *         mutationFn = { title -> api.addTodo(title) },
 *         onSettled = { _, _, _, _ -> client.invalidateQueries("todos") },
 *     ),
 * )
 *
 * addTodo.mutate("Buy milk")            // fire and forget
 * val todo = addTodo.mutateAwait("Buy milk")  // suspends, throws on failure
 * ```
 *
 * Kwery deliberately omits TanStack's **per-call callbacks**. Theirs fire only
 * for the last of several concurrent invocations, and only if the component is
 * still mounted — surprising rules that exist to model React's observer
 * resubscription. In Kotlin the equivalent is to write the follow-up code after
 * [mutateAwait], which always runs, composes, and needs no explanation.
 */
public class Mutation<V, R> internal constructor(
    private val options: MutationOptions<V, R, *>,
    private val coroutineScope: CoroutineScope,
    private val timeSource: TimeSource,
    private val onlineManager: OnlineManager,
    /** Shared by every mutation declaring the same [MutationScope]. */
    private val serialLock: Mutex?,
    private val onMutationStarted: () -> Unit = {},
    private val onMutationSettled: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(MutationState<V, R>())

    public val state: StateFlow<MutationState<V, R>> = mutableState.asStateFlow()

    /**
     * Run the mutation, ignoring the outcome.
     *
     * Failures surface through [state], not as an exception — this is the
     * fire-and-forget path. Returns the [Job] so callers can await or cancel it.
     */
    public fun mutate(variables: V): Job = coroutineScope.launch {
        try {
            execute(variables)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Throwable) {
            // Already reflected in state; rethrowing would take down the scope.
        }
    }

    /**
     * Run the mutation and return its result, throwing the original exception
     * on failure.
     *
     * Named `mutateAwait` rather than TanStack's `mutateAsync`: in Kotlin the
     * suspending variant *is* the natural one, and "Async" would wrongly
     * suggest it does not suspend the caller.
     */
    public suspend fun mutateAwait(variables: V): R = execute(variables)

    /** Return to [MutationStatus.Idle], clearing data, error and variables. */
    public fun reset() {
        mutableState.value = MutationState()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun execute(variables: V): R {
        val typed = options as MutationOptions<V, R, Any?>

        mutableState.value = MutationState(
            status = MutationStatus.Pending,
            variables = variables,
            submittedAt = timeSource.nowMillis(),
        )
        onMutationStarted()

        try {
            return runToCompletion(typed, variables)
        } finally {
            // Non-suspending, so it also runs when the mutation is cancelled.
            onMutationSettled()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun runToCompletion(
        typed: MutationOptions<V, R, Any?>,
        variables: V,
    ): R {
        // Before anything else, and before waiting for a scope turn: an
        // optimistic update must be visible immediately, not after the queue.
        val context = typed.onMutate?.invoke(variables)

        return withSerialTurn {
            try {
                val result = runWithRetry(typed, variables)

                // Callbacks run BEFORE the terminal status is published, so a
                // mutation stays Pending until its onSettled finishes. That is
                // what lets `onSettled = { invalidateQueries(...) }` keep a
                // button disabled until the list has actually refreshed.
                typed.onSuccess?.invoke(result, variables, context)
                typed.onSettled?.invoke(result, null, variables, context)

                mutableState.value = mutableState.value.copy(
                    status = MutationStatus.Success,
                    data = result,
                    error = null,
                    isPaused = false,
                )
                result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // A throw from onError or onSettled must NOT replace the
                // failure the caller actually needs to see. TanStack routes
                // these to an unhandled-rejection channel, which loses them;
                // attaching them as suppressed keeps the original error primary
                // while preserving the callback's failure for diagnosis.
                reportingSuppressed(error) { typed.onError?.invoke(error, variables, context) }
                reportingSuppressed(error) {
                    typed.onSettled?.invoke(null, error, variables, context)
                }

                mutableState.value = mutableState.value.copy(
                    status = MutationStatus.Error,
                    error = error,
                    isPaused = false,
                )
                throw error
            }
        }
    }

    /**
     * Run [block], attaching any failure to [primary] as suppressed rather than
     * letting it escape.
     *
     * Used only on the error path, where a throwing callback must not mask the
     * failure that caused it.
     */
    private suspend fun reportingSuppressed(primary: Throwable, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (callbackFailure: Throwable) {
            if (callbackFailure !== primary) primary.addSuppressed(callbackFailure)
        }
    }

    /** Take this mutation's turn in its scope, reporting [MutationState.isPaused] while queued. */
    private suspend fun <T> withSerialTurn(body: suspend () -> T): T {
        val lock = serialLock ?: return body()
        if (!lock.tryLock()) {
            mutableState.value = mutableState.value.copy(isPaused = true)
            lock.lock()
        }
        return try {
            mutableState.value = mutableState.value.copy(isPaused = false)
            body()
        } finally {
            lock.unlock()
        }
    }

    private suspend fun runWithRetry(
        typed: MutationOptions<V, R, Any?>,
        variables: V,
    ): R {
        var failureCount = 0
        while (true) {
            awaitOnline(typed)
            try {
                return typed.mutationFn(variables)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (!typed.retry.shouldRetry(failureCount, error)) throw error
                val wait = typed.retryDelay.delayFor(failureCount, error)
                failureCount++
                mutableState.value = mutableState.value.copy(failureCount = failureCount)
                delay(wait)
            }
        }
    }

    private suspend fun awaitOnline(typed: MutationOptions<V, R, Any?>) {
        if (typed.networkMode == NetworkMode.Always) return
        // A fast path, not a correctness guard: `first { it }` on an already
        // true StateFlow returns immediately, and StateFlow conflation hides
        // the isPaused true-then-false pair from observers either way. It is
        // here to keep the common case — an online write — from collecting a
        // flow at all. Removing it changes no behaviour any test can see.
        if (onlineManager.isOnline.value) return

        mutableState.value = mutableState.value.copy(isPaused = true)
        onlineManager.isOnline.first { it }
        mutableState.value = mutableState.value.copy(isPaused = false)
    }
}
