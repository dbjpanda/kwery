package dev.kwery.persist

import dev.kwery.OnlineManager
import dev.kwery.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class AddTodo(val title: String)

private object AddTodoKey : DurableMutationKey<AddTodo> {
    override val parts get() = listOf("todos", "add")
    override val serializer get() = serializer<AddTodo>()
}

private object DeleteTodoKey : DurableMutationKey<String> {
    override val parts get() = listOf("todos", "delete")
    override val serializer get() = serializer<String>()
}

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineQueueTest {

    private class TestOnline(initial: Boolean = true) : OnlineManager {
        private val state = MutableStateFlow(initial)
        override val isOnline: StateFlow<Boolean> = state.asStateFlow()
        fun set(value: Boolean) { state.value = value }
    }

    private fun TestScope.settle(ms: Long = 100) {
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    private fun TestScope.time() = TimeSource { testScheduler.currentTime }

    // ---- Durability -------------------------------------------------------

    @Test
    fun `a write survives process death and is delivered on next launch`() = runTest {
        // The store outlives the queue, exactly as a database outlives a process.
        val store = InMemoryMutationQueueStore()
        val online = TestOnline(initial = false)
        val delivered = mutableListOf<AddTodo>()

        // First "process": offline, so nothing is sent.
        val first = OfflineQueue(backgroundScope, OfflineQueueOptions(store), online, time()) {
            register(AddTodoKey) { delivered += it }
        }
        first.submit(AddTodoKey, AddTodo("Buy milk"))
        settle()

        assertTrue(delivered.isEmpty(), "offline: nothing sent")
        assertEquals(1, store.all().size, "but it IS on disk")

        // Second "process": a fresh queue over the same store, now online.
        val online2 = TestOnline(initial = true)
        val second = OfflineQueue(backgroundScope, OfflineQueueOptions(store), online2, time()) {
            register(AddTodoKey) { delivered += it }
        }
        second.resume()
        settle()

        assertEquals(listOf(AddTodo("Buy milk")), delivered)
        assertTrue(store.all().isEmpty(), "delivered writes leave the queue")
    }

    @Test
    fun `the record is stored before the first attempt`() = runTest {
        // The window a store-on-failure design leaves open: a process killed
        // between the user's tap and the network call loses the write.
        val store = InMemoryMutationQueueStore()
        var storedWhenAttempted = 0

        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store), TestOnline(), time()) {
            register(AddTodoKey) { storedWhenAttempted = store.all().size; delay(10) }
        }

        queue.submit(AddTodoKey, AddTodo("a"))
        settle()

        assertEquals(1, storedWhenAttempted, "the write must be on disk before it is attempted")
    }

    @Test
    fun `an unregistered key is rejected at submit, not at resume`() = runTest {
        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(InMemoryMutationQueueStore()), TestOnline(), time()) {
            register(AddTodoKey) { }
        }

        val error = assertFailsWith<IllegalArgumentException> {
            queue.submit(DeleteTodoKey, "1")
        }
        assertTrue(error.message!!.contains("No handler registered"))
    }

    @Test
    fun `a record whose handler is gone after an app update is dead-lettered`() = runTest {
        val store = InMemoryMutationQueueStore()
        val old = OfflineQueue(backgroundScope, OfflineQueueOptions(store), TestOnline(false), time()) {
            register(DeleteTodoKey) { }
        }
        old.submit(DeleteTodoKey, "1")
        settle()

        // Next app version no longer has that mutation at all.
        val updated = OfflineQueue(backgroundScope, OfflineQueueOptions(store), TestOnline(true), time()) {
            register(AddTodoKey) { }
        }
        updated.resume()
        settle()

        val dead = updated.deadLettered()
        assertEquals(1, dead.size)
        assertEquals(DeadLetterReason.Unregistered, dead.single().deadLetter)
    }

    // ---- Ordering ---------------------------------------------------------

    @Test
    fun `writes in a scope replay in submission order`() = runTest {
        val store = InMemoryMutationQueueStore()
        val order = mutableListOf<String>()
        val online = TestOnline(initial = false)

        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store), online, time()) {
            register(AddTodoKey) { order += "start:${it.title}"; delay(50); order += "end:${it.title}" }
        }

        queue.submit(AddTodoKey, AddTodo("a"), scopeId = "todos")
        settle(10)
        queue.submit(AddTodoKey, AddTodo("b"), scopeId = "todos")
        settle(10)
        queue.submit(AddTodoKey, AddTodo("c"), scopeId = "todos")
        settle(10)

        online.set(true)
        settle(1_000)

        assertEquals(
            listOf("start:a", "end:a", "start:b", "end:b", "start:c", "end:c"),
            order,
            "scoped writes must not interleave",
        )
    }

    @Test
    fun `a failing write does not block others in its scope`() = runTest {
        val store = InMemoryMutationQueueStore()
        val delivered = mutableListOf<String>()

        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store), TestOnline(), time()) {
            register(AddTodoKey) {
                if (it.title == "poison") throw IllegalStateException("rejected")
                delivered += it.title
            }
        }

        queue.submit(AddTodoKey, AddTodo("poison"), scopeId = "todos")
        settle(10)
        queue.submit(AddTodoKey, AddTodo("good"), scopeId = "todos")
        settle(500)

        assertEquals(listOf("good"), delivered, "the scope lock must be released on failure")
    }

    // ---- Poison messages --------------------------------------------------

    @Test
    fun `a permanently failing write is dead-lettered and stops retrying`() = runTest {
        // Without a ceiling, a 400 that will never succeed retries forever and
        // blocks everything behind it.
        val store = InMemoryMutationQueueStore()
        var attempts = 0

        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store, maxAttempts = 3), TestOnline(), time()) {
            register(AddTodoKey) { attempts++; throw IllegalStateException("400") }
        }

        queue.submit(AddTodoKey, AddTodo("bad"))
        settle()
        repeat(5) { queue.resume(); settle() }

        assertEquals(3, attempts, "attempts stop at maxAttempts")
        val dead = queue.deadLettered()
        assertEquals(1, dead.size)
        assertEquals(DeadLetterReason.TooManyAttempts, dead.single().deadLetter)
        assertEquals("400", dead.single().lastError)
    }

    @Test
    fun `a stale write is dead-lettered rather than replayed`() = runTest {
        // Replaying a week-old edit against changed server state is usually
        // worse than dropping it and telling the user.
        val store = InMemoryMutationQueueStore()
        var delivered = 0
        val online = TestOnline(initial = false)

        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store, maxAge = 7.days), online, time()) {
            register(AddTodoKey) { delivered++ }
        }
        queue.submit(AddTodoKey, AddTodo("ancient"))
        settle()

        testScheduler.advanceTimeBy(8.days.inWholeMilliseconds)
        online.set(true)
        queue.resume()
        settle()

        assertEquals(0, delivered, "a stale write must not be replayed")
        assertEquals(DeadLetterReason.Expired, queue.deadLettered().single().deadLetter)
    }

    @Test
    fun `a dead-lettered write can be discarded`() = runTest {
        val store = InMemoryMutationQueueStore()
        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store, maxAttempts = 1), TestOnline(), time()) {
            register(AddTodoKey) { throw IllegalStateException("no") }
        }

        queue.submit(AddTodoKey, AddTodo("bad"))
        settle()

        val dead = queue.deadLettered().single()
        queue.discard(dead.id)
        assertTrue(queue.deadLettered().isEmpty())
        assertTrue(store.all().isEmpty())
    }

    // ---- Idempotency and visibility ---------------------------------------

    @Test
    fun `each write carries a stable id for idempotency`() = runTest {
        // Delivery is at-least-once: the write may have reached the server
        // before the process died. The id lets the server recognise a replay.
        val store = InMemoryMutationQueueStore()
        val seenIds = mutableListOf<String>()
        val online = TestOnline(initial = false)

        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store), online, time()) {
            register(AddTodoKey) { }
        }
        val id = queue.submit(AddTodoKey, AddTodo("a"))
        settle()

        seenIds += store.all().single().id
        assertEquals(id, seenIds.single(), "the id returned to the caller is the stored one")

        // Survives a restart unchanged, so a replay is recognisable as one.
        val second = OfflineQueue(backgroundScope, OfflineQueueOptions(store), TestOnline(true), time()) {
            register(AddTodoKey) { }
        }
        second.resume()
        settle()
        assertEquals(id, seenIds.single())
    }

    @Test
    fun `pending count reflects undelivered writes`() = runTest {
        val store = InMemoryMutationQueueStore()
        val online = TestOnline(initial = false)
        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store), online, time()) {
            register(AddTodoKey) { }
        }

        assertEquals(0, queue.pending.value)

        queue.submit(AddTodoKey, AddTodo("a"))
        settle()
        queue.submit(AddTodoKey, AddTodo("b"))
        settle()
        assertEquals(2, queue.pending.value, "an app can show '2 changes pending'")

        online.set(true)
        settle(500)
        assertEquals(0, queue.pending.value)
    }

    @Test
    fun `writes wait for connectivity instead of burning attempts`() = runTest {
        val store = InMemoryMutationQueueStore()
        var attempts = 0
        val online = TestOnline(initial = false)

        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store, maxAttempts = 2), online, time()) {
            register(AddTodoKey) { attempts++ }
        }

        queue.submit(AddTodoKey, AddTodo("a"))
        settle(5.seconds.inWholeMilliseconds)

        assertEquals(0, attempts, "offline must not consume the attempt budget")
        assertTrue(queue.deadLettered().isEmpty())

        online.set(true)
        settle(500)
        assertEquals(1, attempts)
    }

    @Test
    fun `the handler receives the idempotency key and attempt number`() = runTest {
        // Without this the at-least-once guarantee is unusable: the id exists
        // but cannot reach the request. Caught while writing docs/offline.md.
        val store = InMemoryMutationQueueStore()
        val seenKeys = mutableListOf<String>()
        val seenAttempts = mutableListOf<Int>()
        var failuresLeft = 2

        val queue = OfflineQueue(
            backgroundScope,
            OfflineQueueOptions(store, maxAttempts = 5),
            TestOnline(),
            time(),
        ) {
            register(AddTodoKey) {
                seenKeys += idempotencyKey
                seenAttempts += attempt
                if (failuresLeft > 0) {
                    failuresLeft--
                    throw IllegalStateException("transient")
                }
            }
        }

        val id = queue.submit(AddTodoKey, AddTodo("a"))
        settle()
        repeat(3) { queue.resume(); settle() }

        assertTrue(seenKeys.isNotEmpty())
        assertTrue(
            seenKeys.all { it == id },
            "the key must be stable across retries so a replay is recognisable",
        )
        assertEquals(listOf(0, 1, 2), seenAttempts, "attempt count increments")
    }
}
