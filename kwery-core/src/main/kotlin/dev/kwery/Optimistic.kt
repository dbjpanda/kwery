package dev.kwery

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks the optimistic writes currently in flight against one cache entry.
 *
 * The cached value is always `base` with every in-flight transform replayed
 * over it, in submission order. That is what makes concurrent optimistic
 * updates correct — see [QueryClient.optimisticMutation].
 */
internal class OptimisticEntry {
    /** The last value known to come from the server, before any optimistic write. */
    var base: Any? = null

    /** In-flight transforms, keyed by submission id so removal is order-independent. */
    val transforms: LinkedHashMap<Long, (Any?) -> Any?> = LinkedHashMap()

    fun replay(): Any? = transforms.values.fold(base) { value, transform -> transform(value) }
}

internal class OptimisticRegistry {
    private val entries = mutableMapOf<QueryKey<*>, OptimisticEntry>()
    private val mutex = Mutex()
    private val ids = AtomicLong(0)

    fun nextId(): Long = ids.incrementAndGet()

    /**
     * Register a transform and return the value the cache should now hold.
     *
     * [currentData] seeds `base` only for the first in-flight write on this key;
     * later writes must not re-seed it, or an earlier optimistic value would be
     * mistaken for server truth.
     */
    suspend fun begin(
        key: QueryKey<*>,
        id: Long,
        currentData: Any?,
        transform: (Any?) -> Any?,
    ): Any? = mutex.withLock {
        val entry = entries.getOrPut(key) { OptimisticEntry().also { it.base = currentData } }
        entry.transforms[id] = transform
        entry.replay()
    }

    /**
     * Retire a transform.
     *
     * When [committed] the transform is folded **into** `base` before removal,
     * because the server accepted it — that value is now truth. Discarding it
     * instead would revert the cache on success and then move it forward again
     * on the refetch, producing a visible flicker on the happy path.
     *
     * When the write failed the transform is simply dropped, and replaying what
     * remains rolls it back without disturbing any other in-flight write.
     *
     * Returns the recomputed value and whether this was the last in-flight
     * write for the key.
     */
    suspend fun end(
        key: QueryKey<*>,
        id: Long,
        committed: Boolean,
    ): Pair<Any?, Boolean> = mutex.withLock {
        val entry = entries[key] ?: return@withLock null to true
        val transform = entry.transforms.remove(id)
        if (committed && transform != null) entry.base = transform(entry.base)

        if (entry.transforms.isEmpty()) {
            entries.remove(key)
            entry.base to true
        } else {
            entry.replay() to false
        }
    }

    suspend fun isOptimistic(key: QueryKey<*>): Boolean =
        mutex.withLock { entries[key]?.transforms?.isNotEmpty() == true }
}

/**
 * A mutation that updates the cache before the server confirms it.
 *
 * ```kotlin
 * val toggle = client.optimisticMutation(
 *     key = TodoListKey,
 *     apply = { todos, id -> todos?.map { if (it.id == id) it.copy(done = !it.done) else it } },
 * ) { id -> api.toggle(id) }
 * ```
 *
 * The helper encodes the whole choreography, which is four steps that must
 * happen in this order and are easy to get wrong by hand:
 *
 * 1. **Cancel in-flight fetches for the key.** Not optional — a refetch that
 *    resolves after the optimistic write would overwrite it with stale server
 *    data, and the UI would visibly flick back to the old value.
 * 2. Apply the transform to the cached value.
 * 3. On failure, remove the transform.
 * 4. Once the **last** in-flight optimistic write for the key settles,
 *    invalidate so the cache reconverges on server truth.
 *
 * ### Why this is not snapshot-and-restore
 *
 * The obvious implementation snapshots the value in `onMutate` and restores it
 * in `onError`. That is silently wrong with two concurrent mutations on one key:
 * B snapshots a value that already contains A's optimistic write, so if A fails
 * and restores *its* snapshot, **B's write is discarded** even though B is still
 * in flight and may yet succeed.
 *
 * Kwery instead keeps the last server value plus an ordered list of in-flight
 * transforms, and re-derives the cached value by replaying them. Removing A
 * simply replays B over the base. Nothing is lost and no ordering assumption is
 * needed.
 *
 * The requirement this places on [apply]: it must be a **pure function of the
 * value it is given**, since it may be replayed against a different input than
 * the one present when the mutation was submitted. Transforms that identify
 * their target by id (the normal case) satisfy this; ones that depend on
 * position generally do not.
 *
 * @param key the cache entry to update.
 * @param apply the optimistic transform. Receives the current value.
 * @param invalidateOnSettle refetch once the last in-flight write settles.
 */
public suspend fun <V, R, T> QueryClient.optimisticMutation(
    key: QueryKey<T>,
    apply: (current: T?, variables: V) -> T?,
    retry: RetryPolicy = RetryPolicy.ForMutations,
    scope: MutationScope? = null,
    invalidateOnSettle: Boolean = true,
    mutationFn: suspend (V) -> R,
): Mutation<V, R> = mutation(
    MutationOptions<V, R, Long>(
        mutationFn = mutationFn,
        onMutate = { variables -> beginOptimistic(key, variables, apply) },
        // Removal happens only in onSettled, which runs on both paths and after
        // onError. Removing in both would drop the transform twice.
        onSettled = { _, error, _, id ->
            id?.let {
                endOptimistic(
                    key = key,
                    id = it,
                    committed = error == null,
                    invalidate = invalidateOnSettle,
                )
            }
        },
        retry = retry,
        scope = scope,
    ),
)
