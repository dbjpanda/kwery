package dev.kwery.persist

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How far the file-backed stores actually go.
 *
 * `RELEASE.md` says file stores are "fine for hundreds of entries and wrong for
 * tens of thousands" and, until this existed, had no number behind it. Deciding
 * whether to build a Room-backed store on a guess is how you end up building
 * the wrong thing carefully.
 *
 * The load-bearing measurement is **write amplification**, not wall-clock time.
 * A file store rewrites the entire file for every change, so one edit to one
 * entry costs bytes proportional to the whole cache. That is deterministic and
 * assertable; timings are printed for scale but not asserted, because a test
 * that fails on a busy CI machine teaches people to rerun rather than to look.
 */
class FileStoreScaleTest {

    private fun entries(count: Int) = List(count) { i ->
        PersistedEntry(
            keyHash = "[\"todo\",$i]",
            data = "\"a response body of a fairly typical size for a list row $i\"",
            dataUpdatedAt = 1_000L + i,
        )
    }

    private fun snapshot(count: Int) = PersistedClient(
        timestamp = 1_000L,
        buster = "v1",
        entries = entries(count),
    )

    @Test
    fun `one changed entry rewrites the whole file`() = runTest {
        val dir = Files.createTempDirectory("kwery-scale").toFile()
        val file = File(dir, "cache.json")
        val persister = FilePersister(file)

        persister.persist(snapshot(1_000))
        val sizeWith1000 = file.length()

        // Change exactly one entry, of a thousand.
        val changed = snapshot(1_000).let { current ->
            current.copy(
                entries = current.entries.mapIndexed { i, e ->
                    if (i == 0) e.copy(data = "\"changed\"") else e
                },
            )
        }
        persister.persist(changed)

        assertTrue(
            file.length() > sizeWith1000 * 0.9,
            "the whole file is rewritten for a one-entry change — that is the " +
                "cost model, and the reason a Room store exists in the roadmap",
        )
    }

    @Test
    fun `restore round-trips at 100, 1000 and 10000 entries`() = runTest {
        val dir = Files.createTempDirectory("kwery-scale-2").toFile()

        listOf(100, 1_000, 10_000).forEach { count ->
            val file = File(dir, "cache-$count.json")
            val persister = FilePersister(file)
            val data = snapshot(count)

            val writeMs = measureTimeMillis { persister.persist(data) }
            val restored: PersistedClient?
            val readMs = measureTimeMillis { restored = persister.restore() }

            assertEquals(count, restored?.entries?.size, "round-trip must be lossless")
            assertEquals(data.entries.first(), restored?.entries?.first())
            assertEquals(data.entries.last(), restored?.entries?.last())

            // Printed rather than asserted: the shape of the curve is the
            // finding, and a threshold here would fail on a loaded machine.
            println(
                "SCALE entries=$count file=${file.length() / 1024}KiB " +
                    "write=${writeMs}ms restore=${readMs}ms",
            )
        }
    }

    @Test
    fun `the queue store rewrites everything to remove one record`() = runTest {
        val dir = Files.createTempDirectory("kwery-scale-3").toFile()
        val file = File(dir, "queue.json")
        val store = FileMutationQueueStore(file)

        repeat(500) { i ->
            store.put(
                QueuedMutation(
                    id = "id-$i",
                    keyHash = "[\"todos\",\"add\"]",
                    variables = "{\"title\":\"write $i\"}",
                    submittedAt = 1_000L + i,
                    attempts = 0,
                ),
            )
        }
        val full = file.length()
        assertEquals(500, store.all().size)

        store.remove("id-0")

        assertEquals(499, store.all().size)
        assertTrue(
            file.length() > full * 0.9,
            "removing one queued write rewrites the other 499 — fine at this " +
                "size, and the reason the roadmap wants a Room-backed store " +
                "for queues that grow",
        )
    }
}
