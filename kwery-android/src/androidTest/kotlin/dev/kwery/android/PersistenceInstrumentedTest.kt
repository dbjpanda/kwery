package dev.kwery.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kwery.persist.DeadLetterReason
import dev.kwery.persist.FileMutationQueueStore
import dev.kwery.persist.FilePersister
import dev.kwery.persist.PersistedClient
import dev.kwery.persist.PersistedEntry
import dev.kwery.persist.QueuedMutation
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The file stores against real Android storage.
 *
 * JVM tests use temp directories on a desktop filesystem. A device has app
 * sandboxing, a different filesystem, and its own rename semantics, and the
 * store is only useful if it works there.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun freshDir(name: String): File =
        File(context.filesDir, name).also { it.deleteRecursively(); it.mkdirs() }

    @Test
    fun a_cache_written_to_filesDir_survives_a_new_store() = runTest {
        val dir = freshDir("kwery-cache-test")
        val file = File(dir, "cache.json")

        FilePersister(file).persist(
            PersistedClient(
                timestamp = 1_000L,
                buster = "v1",
                entries = List(50) { PersistedEntry("[\"todo\",$it]", "\"row $it\"", 2_000L + it) },
            ),
        )

        // A separate instance, as a cold start would create.
        val restored = FilePersister(file).restore()
        assertEquals(50, restored?.entries?.size)
        assertEquals("v1", restored?.buster)
        assertTrue(file.exists(), "and the file is really on the device filesystem")
    }

    @Test
    fun an_atomic_write_leaves_no_scratch_files_on_device() = runTest {
        val dir = freshDir("kwery-atomic-test")
        val file = File(dir, "cache.json")
        val persister = FilePersister(file)

        repeat(5) { round ->
            persister.persist(
                PersistedClient(
                    timestamp = round.toLong(),
                    buster = "v1",
                    entries = listOf(PersistedEntry("[\"k\"]", "\"round $round\"", round.toLong())),
                ),
            )
        }

        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue(leftovers.isEmpty(), "temp files must not accumulate in app storage: $leftovers")
        assertEquals(1, dir.listFiles()?.size, "exactly the cache file, nothing else")
    }

    @Test
    fun a_queued_write_survives_the_store_being_reopened() = runTest {
        val dir = freshDir("kwery-queue-test")
        val file = File(dir, "queue.json")

        FileMutationQueueStore(file).put(
            QueuedMutation(
                id = "write-1",
                keyHash = "[\"todos\",\"add\"]",
                variables = "{\"title\":\"Buy milk\"}",
                submittedAt = 1_000L,
            ),
        )

        val reopened = FileMutationQueueStore(file).all()
        assertEquals(listOf("write-1"), reopened.map { it.id })
        assertEquals("{\"title\":\"Buy milk\"}", reopened.single().variables)
    }

    @Test
    fun a_dead_lettered_write_keeps_its_reason_across_a_reopen() = runTest {
        val dir = freshDir("kwery-deadletter-test")
        val file = File(dir, "queue.json")

        FileMutationQueueStore(file).put(
            QueuedMutation(
                id = "expired",
                keyHash = "[\"todos\",\"add\"]",
                variables = "{}",
                submittedAt = 1_000L,
                deadLetter = DeadLetterReason.Expired,
            ),
        )

        assertEquals(
            DeadLetterReason.Expired,
            FileMutationQueueStore(file).all().single().deadLetter,
        )
    }
}
