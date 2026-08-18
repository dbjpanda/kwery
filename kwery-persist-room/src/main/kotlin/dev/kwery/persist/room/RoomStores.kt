package dev.kwery.persist.room

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.kwery.persist.DeadLetterReason
import dev.kwery.persist.MutationQueueStore
import dev.kwery.persist.PersistedClient
import dev.kwery.persist.PersistedEntry
import dev.kwery.persist.QueryPersister
import dev.kwery.persist.QueuedMutation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room-backed storage for a cache and an offline queue.
 *
 * Use this instead of the file stores when the cache is large or changes often.
 * The file stores rewrite everything on every change: at ten thousand entries
 * that is over a megabyte of flash per change, which is battery and wear rather
 * than a correctness problem. Rows let SQLite touch only what moved.
 *
 * Restoring is *not* the reason to switch. A file store reads ten thousand
 * entries in single-digit milliseconds, so this is about writes.
 */
public class KweryRoomStorage private constructor(
    private val db: KweryDatabase,
) {
    public val persister: QueryPersister = RoomPersister(db)
    public val queueStore: MutationQueueStore = RoomMutationQueueStore(db)

    /** Close the underlying database. */
    public fun close(): Unit = db.close()

    /** Direct DAO access, for this module's own tests. */
    internal fun rawQueueDao(): QueueDao = db.queueDao()

    public companion object {
        /**
         * Open, or create, a database at [path].
         *
         * On Android pass something under `context.filesDir`. The bundled
         * SQLite driver is used so behaviour does not vary with the platform's
         * own SQLite version.
         */
        public fun at(path: String): KweryRoomStorage =
            KweryRoomStorage(builder(path).build())

        /** An in-memory database, for tests. */
        public fun inMemory(): KweryRoomStorage =
            KweryRoomStorage(
                Room.inMemoryDatabaseBuilder<KweryDatabase>()
                    .setDriver(BundledSQLiteDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build(),
            )

        private fun builder(path: String): RoomDatabase.Builder<KweryDatabase> =
            Room.databaseBuilder<KweryDatabase>(name = path)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
    }
}

/**
 * A [QueryPersister] that writes only the rows that changed.
 *
 * The interface hands over the whole cache on every call, so the diff happens
 * here: fingerprints are compared first, and only genuinely new or updated
 * entries are written. Without that this would be a file persister with extra
 * steps.
 */
internal class RoomPersister(private val db: KweryDatabase) : QueryPersister {
    private val mutex = Mutex()

    override suspend fun persist(client: PersistedClient): Unit = mutex.withLock {
        val dao = db.cacheDao()
        val existing = dao.fingerprints().associate { it.keyHash to it.dataUpdatedAt }
        val incoming = client.entries.associateBy { it.keyHash }

        val changed = client.entries.filter { existing[it.keyHash] != it.dataUpdatedAt }
        val gone = existing.keys - incoming.keys

        dao.applyChanges(
            changed = changed.map { CacheEntryRow(it.keyHash, it.data, it.dataUpdatedAt) },
            gone = gone.toList(),
            meta = CacheMetaRow(
                timestamp = client.timestamp,
                buster = client.buster,
                schemaVersion = client.schemaVersion,
            ),
        )
    }

    override suspend fun restore(): PersistedClient? = mutex.withLock {
        val dao = db.cacheDao()
        val meta = dao.meta() ?: return@withLock null
        PersistedClient(
            timestamp = meta.timestamp,
            buster = meta.buster,
            schemaVersion = meta.schemaVersion,
            entries = dao.entries().map { PersistedEntry(it.keyHash, it.data, it.dataUpdatedAt) },
        )
    }

    override suspend fun remove(): Unit = mutex.withLock {
        db.cacheDao().clearAll()
    }
}

/** A [MutationQueueStore] where removing one write does not rewrite the rest. */
internal class RoomMutationQueueStore(private val db: KweryDatabase) : MutationQueueStore {

    override suspend fun put(record: QueuedMutation) {
        db.queueDao().put(
            QueuedMutationRow(
                id = record.id,
                keyHash = record.keyHash,
                variables = record.variables,
                scopeId = record.scopeId,
                submittedAt = record.submittedAt,
                attempts = record.attempts,
                lastError = record.lastError,
                deadLetter = record.deadLetter?.name,
            ),
        )
    }

    override suspend fun remove(id: String) {
        db.queueDao().remove(id)
    }

    override suspend fun all(): List<QueuedMutation> = db.queueDao().all().map { row ->
        QueuedMutation(
            id = row.id,
            keyHash = row.keyHash,
            variables = row.variables,
            scopeId = row.scopeId,
            submittedAt = row.submittedAt,
            attempts = row.attempts,
            lastError = row.lastError,
            // An unknown name means the enum lost a constant between app
            // versions. Treat it as not dead-lettered rather than crashing on
            // launch: the queue is more valuable than the reason.
            deadLetter = row.deadLetter?.let { name ->
                DeadLetterReason.entries.firstOrNull { it.name == name }
            },
        )
    }
}
