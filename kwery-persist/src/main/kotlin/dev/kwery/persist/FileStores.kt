package dev.kwery.persist

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

private val fileJson = Json { ignoreUnknownKeys = true }

/**
 * Writes [content] to [target] such that a crash mid-write cannot corrupt it.
 *
 * The write goes to a sibling temp file and is then renamed over the target.
 * Rename is atomic on every filesystem Android uses, so a reader sees either
 * the whole old file or the whole new one — never a half-written mixture.
 *
 * Writing in place is the obvious implementation and is wrong in exactly the
 * situation persistence exists for: the process being killed at an arbitrary
 * moment.
 */
internal fun writeAtomically(
    target: File,
    content: String,
    /**
     * Test seam: runs after the temp file is written but before the rename,
     * so a test can simulate a process dying in exactly that window. Without
     * it there is no way to prove the property this function exists for.
     */
    beforeRename: () -> Unit = {},
) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, "${target.name}.tmp")
    temp.writeText(content)
    beforeRename()
    if (!temp.renameTo(target)) {
        // Some filesystems refuse to rename onto an existing file.
        target.delete()
        check(temp.renameTo(target)) { "could not replace ${target.path}" }
    }
}

/**
 * Stores the query cache as a single JSON file.
 *
 * Suitable up to a few hundred KB. Beyond that the whole-blob rewrite on every
 * change becomes the dominant cost, and a row-based store is the better shape.
 *
 * @param file where to write. Must not be shared with a [FileMutationQueueStore] —
 *   the cache is disposable and the queue is not.
 */
public class FilePersister(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : QueryPersister {

    private val mutex = Mutex()

    override suspend fun persist(client: PersistedClient): Unit = mutex.withLock {
        withContext(dispatcher) {
            writeAtomically(file, fileJson.encodeToString(PersistedClient.serializer(), client))
        }
    }

    override suspend fun restore(): PersistedClient? = mutex.withLock {
        withContext(dispatcher) {
            if (!file.exists()) return@withContext null
            // A malformed file is reported by throwing; the caller discards the
            // snapshot and deletes it, which is the right response to corruption.
            fileJson.decodeFromString(PersistedClient.serializer(), file.readText())
        }
    }

    override suspend fun remove(): Unit = mutex.withLock {
        withContext(dispatcher) { file.delete() }
    }
}

/**
 * Stores the offline mutation queue as a single JSON file.
 *
 * Deliberately a **different file** from [FilePersister]'s. The cache is
 * disposable — expired or corrupt snapshots are discarded wholesale — and
 * applying that policy to pending user writes would destroy work someone
 * believes is saved.
 */
public class FileMutationQueueStore(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MutationQueueStore {

    private val mutex = Mutex()

    override suspend fun put(record: QueuedMutation): Unit = mutex.withLock {
        val records = readAll().associateBy { it.id }.toMutableMap()
        records[record.id] = record
        writeAll(records.values.sortedBy { it.submittedAt })
    }

    override suspend fun remove(id: String): Unit = mutex.withLock {
        writeAll(readAll().filterNot { it.id == id })
    }

    override suspend fun all(): List<QueuedMutation> = mutex.withLock { readAll() }

    private suspend fun readAll(): List<QueuedMutation> = withContext(dispatcher) {
        // A fast path, not the guard: the catch below already turns a missing
        // file into an empty queue, since readText throws. This is here so the
        // ordinary first-launch read does not build an exception to discover
        // something a stat call answers. Defence in depth is deliberate —
        // crashing on launch is the worst failure this class can have.
        if (!file.exists()) return@withContext emptyList()
        try {
            fileJson.decodeFromString(QueueFile.serializer(), file.readText()).records
        } catch (corrupt: Throwable) {
            // Unlike the cache, a corrupt queue is not simply discarded and
            // forgotten — but there is nothing recoverable in a malformed file
            // either. Return empty and let the file be rewritten; the
            // alternative is crashing an app on launch.
            emptyList()
        }
    }

    private suspend fun writeAll(records: List<QueuedMutation>) = withContext(dispatcher) {
        writeAtomically(file, fileJson.encodeToString(QueueFile.serializer(), QueueFile(records)))
    }
}

@kotlinx.serialization.Serializable
private data class QueueFile(val records: List<QueuedMutation> = emptyList())
