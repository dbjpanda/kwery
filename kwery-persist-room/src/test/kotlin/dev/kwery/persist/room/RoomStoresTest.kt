package dev.kwery.persist.room

import dev.kwery.persist.DeadLetterReason
import dev.kwery.persist.PersistedClient
import dev.kwery.persist.PersistedEntry
import dev.kwery.persist.QueuedMutation
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room-backed storage, tested on the JVM with the bundled SQLite driver.
 *
 * No device and no Robolectric: Room's KMP runtime and its own bundled SQLite
 * run in an ordinary unit test, which is the only reason this module could be
 * built to the same standard as the rest.
 */
class RoomStoresTest {

    private val open = mutableListOf<KweryRoomStorage>()

    private fun storage(): KweryRoomStorage =
        KweryRoomStorage.inMemory().also { open += it }

    private fun onDisk(dir: File): KweryRoomStorage =
        KweryRoomStorage.at(File(dir, "kwery.db").absolutePath).also { open += it }

    @AfterTest fun close() {
        open.forEach { it.close() }
        open.clear()
    }

    private fun entries(count: Int, from: Int = 0) = List(count) { i ->
        PersistedEntry("[\"todo\",${from + i}]", "\"row ${from + i}\"", 1_000L + from + i)
    }

    private fun snapshot(entries: List<PersistedEntry>) =
        PersistedClient(timestamp = 5_000L, buster = "v1", entries = entries)

    @Test
    fun `a snapshot round-trips`() = runTest {
        val s = storage()
        s.persister.persist(snapshot(entries(3)))

        val restored = s.persister.restore()
        assertEquals(5_000L, restored?.timestamp)
        assertEquals("v1", restored?.buster)
        assertEquals(3, restored?.entries?.size)
        assertEquals(entries(3).toSet(), restored?.entries?.toSet())
    }

    @Test
    fun `restore returns null before anything is written`() = runTest {
        assertNull(storage().persister.restore())
    }

    @Test
    fun `entries removed from the snapshot are deleted`() = runTest {
        val s = storage()
        s.persister.persist(snapshot(entries(5)))
        s.persister.persist(snapshot(entries(2)))

        val restored = s.persister.restore()
        assertEquals(2, restored?.entries?.size, "the other three are gone, not orphaned")
    }

    @Test
    fun `remove clears entries and metadata`() = runTest {
        val s = storage()
        s.persister.persist(snapshot(entries(3)))
        s.persister.remove()
        assertNull(s.persister.restore(), "no metadata means no snapshot, not an empty one")
    }

    @Test
    fun `only changed rows are written`() = runTest {
        // The reason this module exists. A file persister rewrites every byte
        // when one entry changes; this should touch a handful of pages.
        val dir = Files.createTempDirectory("kwery-room-amp").toFile()
        val s = onDisk(dir)
        s.persister.persist(snapshot(entries(2_000)))

        // In WAL mode the .db file stays near-empty until a checkpoint, so the
        // cache on disk is db + wal. Measuring the .db alone reports 4KB for a
        // database holding thousands of rows.
        fun onDiskBytes() = File(dir, "kwery.db").length() + File(dir, "kwery.db-wal").length()

        val whole = onDiskBytes()
        assertTrue(whole > 50_000, "2000 entries should be a substantial database, was ${whole}B")

        val changed = entries(2_000).toMutableList()
        changed[0] = changed[0].copy(data = "\"changed\"", dataUpdatedAt = 9_999L)
        val before = onDiskBytes()
        s.persister.persist(snapshot(changed))
        val dirtied = onDiskBytes() - before

        println("AMPLIFICATION cache=${whole}B  one-row change wrote ${dirtied}B")
        assertTrue(
            dirtied < whole / 4,
            "one changed row should not rewrite the cache: wrote ${dirtied}B against a ${whole}B database",
        )

        assertEquals(
            "\"changed\"",
            s.persister.restore()?.entries?.first { it.keyHash == "[\"todo\",0]" }?.data,
        )
    }

    @Test
    fun `it survives being reopened, as after a process restart`() = runTest {
        val dir = Files.createTempDirectory("kwery-room-restart").toFile()
        val first = onDisk(dir)
        first.persister.persist(snapshot(entries(4)))
        first.close()
        open.remove(first)

        val second = onDisk(dir)
        assertEquals(4, second.persister.restore()?.entries?.size)
    }

    // ---- Queue -----------------------------------------------------------

    private fun queued(id: String, at: Long = 1_000L) = QueuedMutation(
        id = id,
        keyHash = "[\"todos\",\"add\"]",
        variables = "{\"title\":\"$id\"}",
        submittedAt = at,
    )

    @Test
    fun `queued writes round-trip in submission order`() = runTest {
        val s = storage()
        s.queueStore.put(queued("c", at = 3_000))
        s.queueStore.put(queued("a", at = 1_000))
        s.queueStore.put(queued("b", at = 2_000))

        assertEquals(listOf("a", "b", "c"), s.queueStore.all().map { it.id }, "order is by submission")
    }

    @Test
    fun `putting the same id updates rather than duplicates`() = runTest {
        val s = storage()
        s.queueStore.put(queued("a"))
        s.queueStore.put(queued("a").copy(attempts = 3, lastError = "boom"))

        val all = s.queueStore.all()
        assertEquals(1, all.size)
        assertEquals(3, all.single().attempts)
        assertEquals("boom", all.single().lastError)
    }

    @Test
    fun `removing one leaves the rest`() = runTest {
        val s = storage()
        listOf("a", "b", "c").forEachIndexed { i, id -> s.queueStore.put(queued(id, at = 1_000L + i)) }
        s.queueStore.remove("b")
        assertEquals(listOf("a", "c"), s.queueStore.all().map { it.id })
    }

    @Test
    fun `dead-letter reasons round-trip`() = runTest {
        val s = storage()
        s.queueStore.put(queued("a").copy(deadLetter = DeadLetterReason.Expired))
        assertEquals(DeadLetterReason.Expired, s.queueStore.all().single().deadLetter)
    }

    @Test
    fun `a dead-letter reason this version does not know is not fatal`() = runTest {
        // An app update can remove an enum constant while rows written by the
        // previous version are still on disk. Losing the reason is acceptable;
        // crashing on launch is not, and a queue is more valuable than the
        // reason a single entry was parked.
        val s = storage()
        s.rawQueueDao().put(
            QueuedMutationRow(
                id = "a",
                keyHash = "[\"todos\",\"add\"]",
                variables = "{}",
                scopeId = null,
                submittedAt = 1_000L,
                attempts = 0,
                lastError = null,
                deadLetter = "SomethingThisVersionNeverHeardOf",
            ),
        )

        val record = s.queueStore.all().single()
        assertEquals("a", record.id, "the write survives")
        assertNull(record.deadLetter, "the unknown reason is dropped, not fatal")
    }

    @Test
    fun `the queue survives a reopen`() = runTest {
        val dir = Files.createTempDirectory("kwery-room-queue").toFile()
        val first = onDisk(dir)
        first.queueStore.put(queued("a"))
        first.close()
        open.remove(first)

        assertEquals(listOf("a"), onDisk(dir).queueStore.all().map { it.id })
    }
}
