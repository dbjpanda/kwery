package dev.kwery.persist

import dev.kwery.FetchStatus
import dev.kwery.QueryClient
import dev.kwery.QueryClientConfig
import dev.kwery.QueryOptions
import dev.kwery.QueryState
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import dev.kwery.TimeSource
import dev.kwery.optimisticMutation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class Todo(val id: String, val title: String)

private object TodosKey : PersistableQueryKey<List<Todo>> {
    override val parts get() = listOf("todos")
    override val serializer get() = ListSerializer(Todo.serializer())
}

/** A key with no serializer: must never be written to disk. */
private object SecretsKey : dev.kwery.QueryKey<List<Todo>> {
    override val parts get() = listOf("secrets")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersistenceTest {

    private fun TestScope.client(gcTime: kotlin.time.Duration = 25.hours) = QueryClient(
        scope = backgroundScope,
        config = QueryClientConfig(
            timeSource = TimeSource { testScheduler.currentTime },
            defaultQueryOptions = QueryOptions(
                gcTime = gcTime,
                retry = RetryPolicy.Never,
                staleTime = StaleTime.of(10.minutes),
            ),
        ),
    )

    private fun TestScope.settle(ms: Long) {
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    private fun options(
        persister: QueryPersister,
        buster: String = "v1",
        maxAge: kotlin.time.Duration = 24.hours,
    ) = PersistOptions(
        persister = persister,
        keys = listOf(TodosKey),
        buster = buster,
        maxAge = maxAge,
    )

    // ---- Round trip -------------------------------------------------------

    @Test
    fun `a cache survives being written and restored into a new client`() = runTest {
        val persister = InMemoryPersister()
        val todos = listOf(Todo("1", "Buy milk"))

        val first = client()
        first.persist(backgroundScope, options(persister))
        first.setQueryData(TodosKey, todos)
        settle(2_000) // past the throttle window

        // A new client shares nothing but the persister — as after a cold start.
        val second = client()
        val restored = second.persist(backgroundScope, options(persister))

        assertEquals(1, restored.restoredEntryCount)
        assertEquals(todos, second.getQueryData(TodosKey))
        assertEquals(DiscardReason.None, restored.discardReason)
    }

    @Test
    fun `restored data keeps its original age, so freshness is judged correctly`() = runTest {
        // The entire point of persisting: a cache restored two minutes later
        // with a ten-minute staleTime is still fresh and must not refetch.
        val persister = InMemoryPersister()
        val first = client()
        first.persist(backgroundScope, options(persister))
        first.setQueryData(TodosKey, listOf(Todo("1", "a")))
        settle(2_000)
        val writtenAt = testScheduler.currentTime

        settle(2.minutes.inWholeMilliseconds)

        val second = client()
        second.persist(backgroundScope, options(persister))

        var requests = 0
        val job = backgroundScope.launch {
            second.query(TodosKey) { requests++; delay(50); listOf(Todo("1", "fresh")) }.collect { }
        }
        settle(500)

        assertEquals(0, requests, "restored data was still fresh; nothing should refetch")
        assertTrue(second.getQueryState(TodosKey)!!.dataUpdatedAt!! <= writtenAt)
        job.cancel()
    }

    // ---- Discarding -------------------------------------------------------

    @Test
    fun `a buster mismatch discards the stored cache`() = runTest {
        val persister = InMemoryPersister()
        val first = client()
        first.persist(backgroundScope, options(persister, buster = "v1"))
        first.setQueryData(TodosKey, listOf(Todo("1", "a")))
        settle(2_000)

        val second = client()
        val restored = second.persist(backgroundScope, options(persister, buster = "v2"))

        assertEquals(DiscardReason.BusterMismatch, restored.discardReason)
        assertNull(second.getQueryData(TodosKey))
        assertNull(persister.restore(), "a discarded cache is also deleted")
    }

    @Test
    fun `an expired cache is discarded`() = runTest {
        val persister = InMemoryPersister()
        val first = client()
        val writing = first.persist(backgroundScope, options(persister, maxAge = 1.hours))
        first.setQueryData(TodosKey, listOf(Todo("1", "a")))
        settle(2_000)

        // Stop writing before advancing, or the live persist loop would keep
        // re-stamping the snapshot and it would never age.
        writing.close()
        settle(2.hours.inWholeMilliseconds)

        val second = client()
        val restored = second.persist(backgroundScope, options(persister, maxAge = 1.hours))

        assertEquals(DiscardReason.Expired, restored.discardReason)
        assertNull(second.getQueryData(TodosKey))
    }

    @Test
    fun `an unreadable store is discarded rather than crashing`() = runTest {
        val broken = object : QueryPersister {
            override suspend fun persist(client: PersistedClient) = Unit
            override suspend fun restore(): PersistedClient = error("corrupt")
            override suspend fun remove() = Unit
        }

        val restored = client().persist(backgroundScope, options(broken))
        assertEquals(DiscardReason.Unreadable, restored.discardReason)
    }

    @Test
    fun `a schema version mismatch discards the stored cache`() = runTest {
        val persister = InMemoryPersister()
        persister.persist(
            PersistedClient(timestamp = 0, buster = "v1", schemaVersion = 999, entries = emptyList()),
        )

        val restored = client().persist(backgroundScope, options(persister))
        assertEquals(DiscardReason.SchemaMismatch, restored.discardReason)
    }

    @Test
    fun `an entry whose shape changed is dropped without losing the rest`() = runTest {
        val persister = InMemoryPersister()
        persister.persist(
            PersistedClient(
                timestamp = 0,
                buster = "v1",
                entries = listOf(
                    PersistedEntry(
                        keyHash = dev.kwery.encodeKey(TodosKey.parts),
                        data = """[{"totally":"different"}]""",
                        dataUpdatedAt = 0,
                    ),
                ),
            ),
        )

        val client = client()
        val restored = client.persist(backgroundScope, options(persister))

        assertEquals(DiscardReason.None, restored.discardReason, "the snapshot itself was valid")
        assertEquals(0, restored.restoredEntryCount, "the undecodable entry was dropped")
        assertNull(client.getQueryData(TodosKey))
    }

    // ---- Opt-in and exclusion --------------------------------------------

    @Test
    fun `a key without a serializer is never written`() = runTest {
        val persister = InMemoryPersister()
        val client = client()
        client.persist(backgroundScope, options(persister))

        client.setQueryData(SecretsKey, listOf(Todo("1", "classified")))
        client.setQueryData(TodosKey, listOf(Todo("2", "ordinary")))
        settle(2_000)

        val stored = persister.restore()!!
        assertEquals(1, stored.entries.size, "only the persistable key is stored")
        assertTrue(stored.entries.single().keyHash.contains("todos"))
    }

    @Test
    fun `exclude keeps a persistable key out of storage`() = runTest {
        val persister = InMemoryPersister()
        val client = client()
        client.persist(
            backgroundScope,
            PersistOptions(
                persister = persister,
                keys = listOf(TodosKey),
                buster = "v1",
                exclude = { it is PersistableQueryKey<*> },
            ),
        )

        client.setQueryData(TodosKey, listOf(Todo("1", "a")))
        settle(2_000)

        assertTrue(persister.restore()!!.entries.isEmpty())
    }

    // ---- The gcTime guard -------------------------------------------------

    @Test
    fun `a gcTime shorter than maxAge is rejected at construction`() = runTest {
        // TanStack documents this and lets you violate it silently, which
        // quietly defeats the feature: entries are evicted from memory long
        // before the stored copy expires.
        val error = assertFailsWith<IllegalArgumentException> {
            client(gcTime = 5.minutes).persist(
                backgroundScope,
                options(InMemoryPersister(), maxAge = 24.hours),
            )
        }
        assertTrue(error.message!!.contains("gcTime"))
        assertTrue(error.message!!.contains("maxAge"))
    }

    @Test
    fun `a gcTime at least as long as maxAge is accepted`() = runTest {
        client(gcTime = 1.days).persist(
            backgroundScope,
            options(InMemoryPersister(), maxAge = 24.hours),
        )
    }

    // ---- Restore ordering --------------------------------------------------

    @Test
    fun `queries do not fetch while a restore is in progress`() = runTest {
        // A cold start must not race the restore with a network request for
        // data that is about to arrive from disk.
        val slowPersister = object : QueryPersister {
            override suspend fun persist(client: PersistedClient) = Unit
            override suspend fun restore(): PersistedClient? {
                delay(1_000)
                return null
            }
            override suspend fun remove() = Unit
        }

        val client = client()
        var requests = 0

        backgroundScope.launch { client.persist(backgroundScope, options(slowPersister)) }
        settle(10)

        val job = backgroundScope.launch {
            client.query(TodosKey) { requests++; delay(50); listOf(Todo("1", "a")) }.collect { }
        }
        settle(200)

        assertTrue(client.isRestoring.value, "still restoring")
        assertEquals(0, requests, "the query must wait rather than race the restore")
        assertEquals(FetchStatus.Idle, client.getQueryState(TodosKey)?.fetchStatus)

        job.cancel()
    }

    // ---- Optimistic writes -------------------------------------------------

    @Test
    fun `an unconfirmed optimistic write is never persisted`() = runTest {
        // Persisting it would resurrect it on the next launch as though the
        // server had accepted it.
        val persister = InMemoryPersister()
        val client = client()
        client.persist(backgroundScope, options(persister))
        client.setQueryData(TodosKey, listOf(Todo("1", "confirmed")))
        settle(2_000)

        val mutation = client.optimisticMutationForTest()
        mutation.mutate("x")
        settle(50) // optimistic value applied, mutation still in flight
        settle(2_000) // a persist window passes while it is still unconfirmed

        val stored = persister.restore()!!
        val storedTodos = stored.entries.firstOrNull()?.data
        assertTrue(
            storedTodos == null || !storedTodos.contains("optimistic"),
            "an unconfirmed write must not reach storage, was: $storedTodos",
        )
    }

    private suspend fun QueryClient.optimisticMutationForTest() =
        optimisticMutation<String, Unit, List<Todo>>(
            key = TodosKey,
            apply = { todos, _ -> (todos ?: emptyList()) + Todo("2", "optimistic") },
            invalidateOnSettle = false,
        ) { delay(60_000) }
}
