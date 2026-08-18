package dev.kwery.persist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class FileStoresTest {

    private fun tempDir(): File = Files.createTempDirectory("kwery-test").toFile()

    private fun snapshot(vararg entries: PersistedEntry) = PersistedClient(
        timestamp = 1_000,
        buster = "v1",
        entries = entries.toList(),
    )

    // ---- FilePersister ----------------------------------------------------

    @Test
    fun `a snapshot round-trips through the file`() = runTest {
        val file = File(tempDir(), "cache.json")
        val persister = FilePersister(file, Dispatchers.Unconfined)
        val written = snapshot(PersistedEntry("todos", """[{"id":"1"}]""", 500))

        persister.persist(written)
        assertEquals(written, persister.restore())
    }

    @Test
    fun `restore returns null when nothing has been written`() = runTest {
        val persister = FilePersister(File(tempDir(), "absent.json"), Dispatchers.Unconfined)
        assertNull(persister.restore())
    }

    @Test
    fun `remove deletes the file`() = runTest {
        val file = File(tempDir(), "cache.json")
        val persister = FilePersister(file, Dispatchers.Unconfined)
        persister.persist(snapshot())
        assertTrue(file.exists())

        persister.remove()
        assertFalse(file.exists())
        assertNull(persister.restore())
    }

    @Test
    fun `a corrupt file surfaces as a failure rather than bad data`() = runTest {
        // The caller (persist()) turns this into DiscardReason.Unreadable and
        // deletes the file, which is the right response to corruption. Silently
        // returning empty would look like "no cache" and hide the problem.
        val file = File(tempDir(), "cache.json")
        file.writeText("{ this is not json")

        val persister = FilePersister(file, Dispatchers.Unconfined)
        assertFailsWith<Throwable> { persister.restore() }
    }

    @Test
    fun `the parent directory is created if missing`() = runTest {
        val file = File(tempDir(), "nested/deeper/cache.json")
        val persister = FilePersister(file, Dispatchers.Unconfined)

        persister.persist(snapshot())
        assertTrue(file.exists())
    }

    @Test
    fun `a write leaves no temp file behind`() = runTest {
        val dir = tempDir()
        val file = File(dir, "cache.json")
        FilePersister(file, Dispatchers.Unconfined).persist(snapshot())

        assertEquals(
            listOf("cache.json"),
            dir.list()!!.sorted(),
            "the temp file used for the atomic write must be renamed, not left",
        )
    }

    @Test
    fun `a stray temp file is ignored by a read`() = runTest {
        val dir = tempDir()
        val file = File(dir, "cache.json")
        val persister = FilePersister(file, Dispatchers.Unconfined)
        val good = snapshot(PersistedEntry("todos", """["original"]""", 1))
        persister.persist(good)

        File(dir, "cache.json.tmp").writeText("{ half written")

        assertEquals(good, persister.restore())
    }

    @Test
    fun `a process dying between write and rename leaves the old file intact`() = runTest {
        // The property atomic writing exists for, tested directly rather than
        // inferred. An in-place write fails this: the target would already hold
        // the new bytes, or half of them.
        val dir = tempDir()
        val target = File(dir, "cache.json")
        writeAtomically(target, "ORIGINAL")

        assertFailsWith<IllegalStateException> {
            writeAtomically(target, "REPLACEMENT") { error("process killed") }
        }

        assertEquals(
            "ORIGINAL",
            target.readText(),
            "the target must still hold the previous content, not the new one",
        )
    }

    @Test
    fun `the replacement lands in full once the rename completes`() = runTest {
        val target = File(tempDir(), "cache.json")
        writeAtomically(target, "ORIGINAL")
        writeAtomically(target, "REPLACEMENT")
        assertEquals("REPLACEMENT", target.readText())
    }

    // ---- FileMutationQueueStore -------------------------------------------

    private fun record(id: String, at: Long = 0) = QueuedMutation(
        id = id,
        keyHash = """["todos","add"]""",
        variables = """{"title":"a"}""",
        submittedAt = at,
    )

    @Test
    fun `queued writes round-trip and keep submission order`() = runTest {
        val store = FileMutationQueueStore(File(tempDir(), "queue.json"), Dispatchers.Unconfined)

        store.put(record("c", at = 30))
        store.put(record("a", at = 10))
        store.put(record("b", at = 20))

        assertEquals(listOf("a", "b", "c"), store.all().map { it.id })
    }

    @Test
    fun `putting the same id updates rather than duplicates`() = runTest {
        val store = FileMutationQueueStore(File(tempDir(), "queue.json"), Dispatchers.Unconfined)

        store.put(record("a"))
        store.put(record("a").copy(attempts = 3))

        assertEquals(1, store.all().size)
        assertEquals(3, store.all().single().attempts)
    }

    @Test
    fun `removing leaves the rest`() = runTest {
        val store = FileMutationQueueStore(File(tempDir(), "queue.json"), Dispatchers.Unconfined)
        store.put(record("a", 1))
        store.put(record("b", 2))

        store.remove("a")
        assertEquals(listOf("b"), store.all().map { it.id })
    }

    @Test
    fun `a corrupt queue file does not crash the app on launch`() = runTest {
        // Different policy from the cache deliberately. There is nothing
        // recoverable in a malformed file either way, but crashing an app on
        // launch because its queue file is damaged is the worse outcome.
        val file = File(tempDir(), "queue.json")
        file.writeText("{{{ not json")

        val store = FileMutationQueueStore(file, Dispatchers.Unconfined)
        assertEquals(emptyList<QueuedMutation>(), store.all())

        // And it recovers: the next write rewrites the file.
        store.put(record("a"))
        assertEquals(listOf("a"), store.all().map { it.id })
    }

    @Test
    fun `the queue survives being reopened, as after a process restart`() = runTest {
        val file = File(tempDir(), "queue.json")
        FileMutationQueueStore(file, Dispatchers.Unconfined).put(record("a"))

        val reopened = FileMutationQueueStore(file, Dispatchers.Unconfined)
        assertEquals(listOf("a"), reopened.all().map { it.id })
    }
}

class FileStoreFirstLaunchTest {

    @Test
    fun `a queue store with no file yet reads as empty`() = runTest {
        // First launch: the directory exists, the file does not. Anything that
        // throws here crashes the app before it has drawn a frame, which is the
        // worst possible time and the hardest to reproduce later.
        val dir = Files.createTempDirectory("kwery-first-launch").toFile()
        val store = FileMutationQueueStore(File(dir, "queue.json"))

        assertEquals(emptyList<QueuedMutation>(), store.all())
    }

    @Test
    fun `a persister with no file yet restores null`() = runTest {
        val dir = Files.createTempDirectory("kwery-first-launch-2").toFile()
        val persister = FilePersister(File(dir, "cache.json"))

        assertNull(persister.restore())
    }
}
