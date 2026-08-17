package dev.kwery.persist

import dev.kwery.OnlineManager
import dev.kwery.TimeSource
import dev.kwery.encodeKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * A write that can outlive the process.
 *
 * Serialized state cannot carry code, so a queued mutation stores only its
 * *variables* and the identity of the function to run. That function must be
 * registered at startup — this is the constraint behind TanStack's
 * `setMutationDefaults` and its `No mutationFn found` error, made explicit.
 */
public interface DurableMutationKey<V> {
    public val parts: List<Any?>
    public val serializer: KSerializer<V>
}

/**
 * What a durable write's handler is told about the attempt it is running.
 *
 * Exists so [idempotencyKey] can actually reach the request. Delivery is
 * at-least-once, so without a way to pass this to the server the guarantee is
 * unusable — which is exactly what it looked like before the documentation for
 * this feature tried to give an example.
 */
public class DurableMutationScope internal constructor(
    /**
     * Stable identity for this write, unchanged across retries and restarts.
     *
     * Send it as an idempotency key so the server can recognise a replay. A
     * non-idempotent endpoint given no key **will** be duplicated eventually.
     */
    public val idempotencyKey: String,

    /** Failed attempts so far. 0 on the first try. */
    public val attempt: Int,
)

/** Why a queued write will never be attempted again. */
public enum class DeadLetterReason {
    /** Failed more times than [OfflineQueueOptions.maxAttempts]. */
    TooManyAttempts,

    /** Sat in the queue longer than [OfflineQueueOptions.maxAge]. */
    Expired,

    /** Its mutation function was not registered when the queue was resumed. */
    Unregistered,
}

/** One queued write, as stored. */
@Serializable
public data class QueuedMutation(
    /**
     * Stable identity for this write, minted at enqueue.
     *
     * Delivery is **at-least-once**, not exactly-once: a write may have reached
     * the server before the process died. Send this as an idempotency key so
     * the server can recognise a replay — Kwery cannot make a non-idempotent
     * endpoint safe on its own.
     */
    val id: String,

    /** Canonical encoding of the [DurableMutationKey]'s parts. */
    val keyHash: String,

    /** Serialized variables. */
    val variables: String,

    /** Writes sharing a scope replay in submission order. */
    val scopeId: String? = null,

    val submittedAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    val deadLetter: DeadLetterReason? = null,
)

/** Durable storage for the queue. Deliberately separate from the cache's store. */
public interface MutationQueueStore {
    public suspend fun put(record: QueuedMutation)
    public suspend fun remove(id: String)
    public suspend fun all(): List<QueuedMutation>
}

/** In-memory queue store, for tests and as a reference implementation. */
public class InMemoryMutationQueueStore : MutationQueueStore {
    private val records = LinkedHashMap<String, QueuedMutation>()
    private val mutex = Mutex()

    override suspend fun put(record: QueuedMutation): Unit =
        mutex.withLock { records[record.id] = record }

    override suspend fun remove(id: String): Unit = mutex.withLock { records.remove(id); Unit }

    override suspend fun all(): List<QueuedMutation> = mutex.withLock { records.values.toList() }
}

public class OfflineQueueOptions(
    public val store: MutationQueueStore,

    /**
     * Attempts before a write is dead-lettered.
     *
     * Without a ceiling, a permanently failing write — a 400 that will never
     * succeed — retries forever and, worse, blocks every later write in its
     * scope. Dead-lettering unblocks the queue and surfaces the failure.
     */
    public val maxAttempts: Int = 5,

    /**
     * How long a write may sit unsent before it is dead-lettered.
     *
     * Replaying a week-old edit against changed server state is usually worse
     * than dropping it and telling the user.
     */
    public val maxAge: Duration = 7.days,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * A durable, ordered queue of writes that survives process death.
 *
 * This is where Kwery decisively beats TanStack. In a browser tab a paused
 * mutation dying with the tab is tolerable; on Android the OS kills processes
 * routinely, and a lost write is a lost write.
 *
 * ```kotlin
 * val queue = OfflineQueue(options, onlineManager, timeSource) {
 *     register(AddTodoKey) { input -> api.addTodo(input) }
 *     register(DeleteTodoKey) { id -> api.deleteTodo(id) }
 * }
 *
 * queue.submit(AddTodoKey, AddTodoInput("Buy milk"))  // enqueued, then attempted
 * queue.resume()                                       // after a cold start
 * ```
 *
 * Registration happens at construction, away from any screen, precisely because
 * a resumed write has no UI to get its function from.
 */
public class OfflineQueue(
    /**
     * Where delivery runs.
     *
     * Delivery is deliberately **not** on the caller's coroutine: a user
     * tapping "save" while offline must not have their coroutine parked until
     * connectivity returns, possibly for hours. [submit] durably enqueues and
     * returns; delivery happens here.
     */
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val options: OfflineQueueOptions,
    private val onlineManager: OnlineManager,
    private val timeSource: TimeSource,
    register: Registrar.() -> Unit,
) {
    /** Collects the mutation functions available for replay. */
    public class Registrar internal constructor() {
        internal val handlers = mutableMapOf<String, Handler<*>>()

        public fun <V> register(
            key: DurableMutationKey<V>,
            fn: suspend DurableMutationScope.(V) -> Unit,
        ) {
            handlers[encodeKey(key.parts)] = Handler(key, fn)
        }
    }

    internal class Handler<V>(
        val key: DurableMutationKey<V>,
        val fn: suspend DurableMutationScope.(V) -> Unit,
    ) {
        suspend fun run(scope: DurableMutationScope, rawVariables: String) {
            scope.fn(json.decodeFromString(key.serializer, rawVariables))
        }
    }

    private val handlers: Map<String, Handler<*>> = Registrar().apply(register).handlers

    private val scopeLocks = mutableMapOf<String, Mutex>()
    private val scopeLockGuard = Mutex()

    private val pendingCount = MutableStateFlow(0)

    /**
     * How many writes are waiting to be delivered.
     *
     * Exposed so an app can show "3 changes pending" — the visible half of
     * offline support, and cheap to provide once the queue exists.
     */
    public val pending: StateFlow<Int> get() = pendingCount.asStateFlow()

    /**
     * Durably enqueue a write, then attempt it in the background.
     *
     * Returns as soon as the write is **on disk**, not when it is delivered.
     *
     * The record is stored *before* the first attempt: a process killed between
     * the user's tap and the network call must not lose the write, which is
     * exactly the window a store-on-failure design leaves open.
     */
    public suspend fun <V> submit(
        key: DurableMutationKey<V>,
        variables: V,
        scopeId: String? = null,
        id: String = newId(),
    ): String {
        val keyHash = encodeKey(key.parts)
        require(handlers.containsKey(keyHash)) {
            "No handler registered for durable mutation $keyHash. Register it when " +
                "constructing the OfflineQueue — a resumed write has no screen to " +
                "get its function from."
        }

        val record = QueuedMutation(
            id = id,
            keyHash = keyHash,
            variables = json.encodeToString(key.serializer, variables),
            scopeId = scopeId,
            submittedAt = timeSource.nowMillis(),
        )
        options.store.put(record)
        refreshPending()

        scope.launch { deliver(record) }
        return id
    }

    /**
     * Attempt every queued write.
     *
     * Call after a cold start, and after the cache has been restored — an
     * optimistic write replayed against an empty cache would write into
     * nothing.
     */
    public suspend fun resume() {
        val queued = options.store.all()
            .filter { it.deadLetter == null }
            .sortedBy { it.submittedAt }

        for (record in queued) scope.launch { deliver(record) }
        refreshPending()
    }

    /** Writes that will never be attempted again, for surfacing to the user. */
    public suspend fun deadLettered(): List<QueuedMutation> =
        options.store.all().filter { it.deadLetter != null }

    /** Forget a dead-lettered write. */
    public suspend fun discard(id: String) {
        options.store.remove(id)
        refreshPending()
    }

    private suspend fun deliver(record: QueuedMutation) {
        val handler = handlers[record.keyHash]
        if (handler == null) {
            // Its function was registered when it was enqueued but is not now —
            // the app was updated and the mutation removed. It can never run.
            deadLetter(record, DeadLetterReason.Unregistered)
            return
        }

        if (isExpired(record)) {
            deadLetter(record, DeadLetterReason.Expired)
            return
        }

        withScopeTurn(record.scopeId) {
            // Wait for connectivity rather than burning an attempt on a request
            // that cannot possibly succeed.
            if (!onlineManager.isOnline.value) onlineManager.isOnline.first { it }

            // Re-check expiry AFTER waiting. This is the whole point of the
            // feature — a write can sit here for days waiting for a network —
            // so checking only on entry would happily replay a week-old edit
            // the moment connectivity returned.
            if (isExpired(record)) {
                deadLetter(record, DeadLetterReason.Expired)
                return@withScopeTurn
            }

            try {
                handler.run(
                    DurableMutationScope(
                        idempotencyKey = record.id,
                        attempt = record.attempts,
                    ),
                    record.variables,
                )
                options.store.remove(record.id)
            } catch (failure: Throwable) {
                val attempts = record.attempts + 1
                val failed = record.copy(
                    attempts = attempts,
                    lastError = failure.message ?: failure::class.simpleName,
                )
                if (attempts >= options.maxAttempts) {
                    deadLetter(failed, DeadLetterReason.TooManyAttempts)
                } else {
                    options.store.put(failed)
                }
            }
            refreshPending()
        }
    }

    private fun isExpired(record: QueuedMutation): Boolean =
        timeSource.nowMillis() - record.submittedAt > options.maxAge.inWholeMilliseconds

    private suspend fun deadLetter(record: QueuedMutation, reason: DeadLetterReason) {
        options.store.put(record.copy(deadLetter = reason))
        refreshPending()
    }

    /**
     * Serialise delivery within a scope, so writes replay in submission order.
     *
     * The lock is released even when the write fails, so one bad record cannot
     * deadlock everything queued behind it.
     */
    private suspend fun withScopeTurn(scopeId: String?, body: suspend () -> Unit) {
        if (scopeId == null) return body()
        val lock = scopeLockGuard.withLock { scopeLocks.getOrPut(scopeId) { Mutex() } }
        lock.withLock { body() }
    }

    private suspend fun refreshPending() {
        pendingCount.value = options.store.all().count { it.deadLetter == null }
    }

    private companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
