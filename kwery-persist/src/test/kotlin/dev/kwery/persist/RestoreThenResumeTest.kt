package dev.kwery.persist

import dev.kwery.OnlineManager
import dev.kwery.QueryClient
import dev.kwery.QueryClientConfig
import dev.kwery.QueryOptions
import dev.kwery.RetryPolicy
import dev.kwery.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@Serializable
private data class RenameTodo(val id: String, val title: String)

private object RenameKey : DurableMutationKey<RenameTodo> {
    override val parts get() = listOf("todos", "rename")
    override val serializer get() = serializer<RenameTodo>()
}

private object TodoListKey : PersistableQueryKey<List<String>> {
    override val parts get() = listOf("todos")
    override val serializer get() = ListSerializer(String.serializer())
}

/**
 * Cold start: the cache is restored, *then* the queue resumes.
 *
 * The order is load-bearing rather than stylistic. A queued optimistic write
 * replayed against an empty cache writes into nothing — the user's edit is sent
 * to the server but vanishes from the screen until the next fetch, which looks
 * exactly like the write being lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreThenResumeTest {

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

    private fun TestScope.client() = QueryClient(
        scope = backgroundScope,
        config = QueryClientConfig(
            timeSource = TimeSource { testScheduler.currentTime },
            defaultQueryOptions = QueryOptions(retry = RetryPolicy.Never, gcTime = 2.hours),
        ),
    )

    @Test
    fun `a queued write replays onto the restored cache, not an empty one`() = runTest {
        val persister = InMemoryPersister()
        val store = InMemoryMutationQueueStore()
        val online = TestOnline(initial = false)
        val time = TimeSource { testScheduler.currentTime }

        // --- First process: cache populated, write queued while offline ---
        run {
            val client = client()
            client.persist(
                backgroundScope,
                PersistOptions(persister, keys = listOf(TodoListKey), maxAge = 1.hours),
            )
            client.setQueryData(TodoListKey) { listOf("Buy milk") }
            settle(2_000)

            val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store), online, time) {
                register(RenameKey) { }
            }
            queue.submit(RenameKey, RenameTodo("1", "Buy oat milk"))
            settle()
            assertEquals(1, store.all().size, "the write is durable before the process dies")
            client.close()
        }

        // --- Second process: restore, then resume ---
        val client = client()
        val seenDuringReplay = mutableListOf<List<String>?>()

        client.persist(
            backgroundScope,
            PersistOptions(persister, keys = listOf(TodoListKey), maxAge = 1.hours),
        )
        settle()

        // persist() suspends until the restore has finished, so simply calling
        // resume() after it is enough — the ordering needs no extra ceremony,
        // which is why the API has none.
        assertEquals(
            listOf("Buy milk"),
            client.getQueryData(TodoListKey),
            "the cache is populated before the queue is touched",
        )

        online.set(true)
        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store), online, time) {
            register(RenameKey) { variables ->
                // What a real handler does: apply the write optimistically to
                // whatever is cached. If this runs before hydration, it sees
                // null and the edit disappears from the screen.
                seenDuringReplay += client.getQueryData(TodoListKey)
                client.setQueryData(TodoListKey) { current ->
                    current?.map { if (it == "Buy milk") variables.title else it }
                }
            }
        }
        queue.resume()
        settle(2_000)

        assertEquals<List<List<String>?>>(
            listOf(listOf("Buy milk")),
            seenDuringReplay,
            "the replayed write saw restored data, not an empty cache",
        )
        assertEquals(
            listOf("Buy oat milk"),
            client.getQueryData(TodoListKey),
            "and its effect is visible on screen",
        )
        assertTrue(store.all().isEmpty(), "delivered writes leave the queue")
        client.close()
    }

    @Test
    fun `resuming before a restore is what the ordering exists to prevent`() = runTest {
        val persister = InMemoryPersister()
        val store = InMemoryMutationQueueStore()
        val online = TestOnline(initial = false)
        val time = TimeSource { testScheduler.currentTime }

        run {
            val client = client()
            client.persist(
                backgroundScope,
                PersistOptions(persister, keys = listOf(TodoListKey), maxAge = 1.hours),
            )
            client.setQueryData(TodoListKey) { listOf("Buy milk") }
            settle(2_000)
            val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store), online, time) {
                register(RenameKey) { }
            }
            queue.submit(RenameKey, RenameTodo("1", "Buy oat milk"))
            settle()
            client.close()
        }

        // Deliberately wrong order: resume without restoring first.
        val client = client()
        val seenDuringReplay = mutableListOf<List<String>?>()
        online.set(true)
        val queue = OfflineQueue(backgroundScope, OfflineQueueOptions(store), online, time) {
            register(RenameKey) { seenDuringReplay += client.getQueryData(TodoListKey) }
        }
        queue.resume()
        settle(2_000)

        // This is the failure mode, pinned so it cannot be mistaken for a bug
        // in the queue later: the write IS delivered, but it had nothing to
        // update. Restore first.
        assertEquals<List<List<String>?>>(
            listOf(null),
            seenDuringReplay,
            "an empty cache is what an early resume sees",
        )
        assertTrue(store.all().isEmpty(), "the write still reached the server")
        client.close()
    }
}
