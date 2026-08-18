package dev.kwery.persist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What happens when two writers share one store.
 *
 * A `QueryClient` is single-process, so two processes hold independent caches.
 * They can still end up pointed at the same file: a widget provider, a
 * `:remote` service, a WorkManager job in another process. Losing an update
 * there is acceptable. Corrupting the store is not, because the failure lands
 * on the next cold start of the main app rather than on whoever caused it.
 */
class MultiProcessSafetyTest {

    private fun snapshot(tag: String, count: Int) = PersistedClient(
        timestamp = 1_000L,
        buster = "v1",
        entries = List(count) { PersistedEntry("[\"k\",$it]", "\"$tag-$it\"", 1_000L + it) },
    )

    @Test
    fun `two persisters writing the same file concurrently never corrupt it`() = runTest {
        val dir = Files.createTempDirectory("kwery-multiproc").toFile()
        val file = File(dir, "cache.json")

        // Two independent instances, exactly as two processes would have. They
        // share no lock: each has its own Mutex, which protects nothing across
        // instances.
        val a = FilePersister(file)
        val b = FilePersister(file)

        withContext(Dispatchers.IO) {
            (1..40).map { round ->
                listOf(
                    async { a.persist(snapshot("a", 60)) },
                    async { b.persist(snapshot("b", 60)) },
                )
            }.flatten().awaitAll()
        }

        // The last writer wins, and losing the other update is fine. What must
        // not happen is a file that is neither.
        val restored = a.restore()
        assertNotNull(restored, "the store must still be readable")
        assertEquals(60, restored.entries.size, "and complete, not truncated")

        val tags = restored.entries.map { it.data.substringAfter('"').substringBefore('-') }.toSet()
        assertEquals(
            1,
            tags.size,
            "every entry must come from one writer: a mixture means a torn write, saw $tags",
        )
    }

    @Test
    fun `a reader never sees a partially written file`() = runTest {
        val dir = Files.createTempDirectory("kwery-multiproc-read").toFile()
        val file = File(dir, "cache.json")
        val writer = FilePersister(file)
        val reader = FilePersister(file)
        writer.persist(snapshot("a", 80))

        val reads = withContext(Dispatchers.IO) {
            val writes = (1..30).map { async { writer.persist(snapshot("b", 80)) } }
            val results = (1..60).map { async { runCatching { reader.restore() } } }
            writes.awaitAll()
            results.awaitAll()
        }

        // Atomic rename is what makes this true: a reader opens either the old
        // file or the new one, never a half-written one.
        reads.forEachIndexed { i, r ->
            assertTrue(r.isSuccess, "read $i failed: ${r.exceptionOrNull()}")
            assertEquals(80, r.getOrNull()?.entries?.size, "read $i was short")
        }
    }

    @Test
    fun `two queue stores sharing a file do not corrupt it`() = runTest {
        val dir = Files.createTempDirectory("kwery-multiproc-queue").toFile()
        val file = File(dir, "queue.json")
        val a = FileMutationQueueStore(file)
        val b = FileMutationQueueStore(file)

        fun record(id: String) = QueuedMutation(
            id = id,
            keyHash = "[\"todos\",\"add\"]",
            variables = "{}",
            submittedAt = 1_000L,
        )

        withContext(Dispatchers.IO) {
            (1..30).map { i ->
                listOf(
                    async { a.put(record("a$i")) },
                    async { b.put(record("b$i")) },
                )
            }.flatten().awaitAll()
        }

        // Interleaved read-modify-write across instances loses records. That is
        // expected and documented. The file staying parseable is not optional.
        val all = a.all()
        assertTrue(all.isNotEmpty(), "the queue must survive, even if entries were lost")
        assertTrue(all.all { it.keyHash == "[\"todos\",\"add\"]" }, "and every record must be intact")
    }
}
