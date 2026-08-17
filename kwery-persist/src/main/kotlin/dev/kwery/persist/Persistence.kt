package dev.kwery.persist

import dev.kwery.QueryKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * A query key whose data can be written to disk.
 *
 * Persistence is **opt-in per key**, by implementing this instead of plain
 * [QueryKey]:
 *
 * ```kotlin
 * data class TodoListKey(val filter: String) : PersistableQueryKey<List<Todo>> {
 *     override val parts get() = listOf("todos", filter)
 *     override val serializer get() = ListSerializer(Todo.serializer())
 * }
 * ```
 *
 * Making the serializer part of the key — rather than a separate registry keyed
 * by class — means a persisted query cannot be misconfigured: the type that
 * gets written is the type the key declares. It also makes opting **out**
 * trivial and obvious, since a key that should never touch disk (a search
 * containing personal data, a one-off) simply stays a `QueryKey`.
 */
public interface PersistableQueryKey<T> : QueryKey<T> {
    public val serializer: KSerializer<T>
}

/** The whole persisted cache, as written by a [QueryPersister]. */
@Serializable
public data class PersistedClient(
    /** When this snapshot was written. Compared against `maxAge` on restore. */
    val timestamp: Long,

    /** Application-supplied cache version. A mismatch discards the snapshot. */
    val buster: String,

    /**
     * Kwery's own storage format version, independent of [buster].
     *
     * Lets Kwery change its on-disk shape without requiring every application
     * to bump its own buster, and vice versa.
     */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,

    val entries: List<PersistedEntry> = emptyList(),
) {
    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** One cache entry in storage. */
@Serializable
public data class PersistedEntry(
    /** Canonical encoding of the key's `parts`, stable across processes. */
    val keyHash: String,

    /** The entry's data, serialized by its key's declared serializer. */
    val data: String,

    val dataUpdatedAt: Long,
)

/**
 * Where a persisted cache is stored.
 *
 * Deliberately three methods with whole-cache granularity, matching TanStack.
 * Resisting per-entry persistence is what keeps implementations trivial; a
 * row-based store is a different persister, not a more complex interface.
 */
public interface QueryPersister {
    public suspend fun persist(client: PersistedClient)

    /** The stored snapshot, or null if there is none or it cannot be read. */
    public suspend fun restore(): PersistedClient?

    public suspend fun remove()
}

/** Keeps a cache in memory. For tests, and as a reference implementation. */
public class InMemoryPersister : QueryPersister {
    private var stored: PersistedClient? = null

    /** Number of writes performed. Lets tests assert throttling. */
    public var writeCount: Int = 0
        private set

    override suspend fun persist(client: PersistedClient) {
        stored = client
        writeCount++
    }

    override suspend fun restore(): PersistedClient? = stored

    override suspend fun remove() {
        stored = null
    }
}
