package dev.kwery.test

import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import dev.kwery.optimisticMutation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class Item(val id: String, val done: Boolean)

private object ItemsKey : QueryKey<List<Item>> {
    override val parts get() = listOf("items")
}

/** Feature 12 — optimistic updates and rollback. */
class OptimisticUpdateTest {

    private val fresh = QueryOptions(
        staleTime = StaleTime.of(5.minutes),
        retry = RetryPolicy.Never,
    )

    /** Toggles the item with the given id. Identifies by id, so it is replay-safe. */
    private val toggle: (List<Item>?, String) -> List<Item>? = { items, id ->
        items?.map { if (it.id == id) it.copy(done = !it.done) else it }
    }

    private suspend fun TestQueryClient.seed(vararg items: Item) {
        client.setQueryData(ItemsKey, items.toList())
    }

    @Test
    fun `the optimistic value is visible before the server responds`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.seed(Item("a", done = false))

        val mutation = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey,
            apply = toggle,
            invalidateOnSettle = false,
        ) { delay(1_000) }

        mutation.mutate("a")
        kwery.settle(50.milliseconds) // long before the mutation resolves

        assertEquals(listOf(Item("a", done = true)), kwery.client.getQueryData(ItemsKey))
        assertTrue(kwery.client.isOptimistic(ItemsKey))
        assertTrue(kwery.client.getQueryState(ItemsKey)!!.isOptimistic)
    }

    @Test
    fun `a failure rolls the value back`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.seed(Item("a", done = false))

        val mutation = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey,
            apply = toggle,
            invalidateOnSettle = false,
        ) { delay(100); throw IllegalStateException("rejected") }

        mutation.mutate("a")
        kwery.settle(50.milliseconds)
        assertEquals(listOf(Item("a", done = true)), kwery.client.getQueryData(ItemsKey))

        kwery.settle(500.milliseconds)
        assertEquals(
            listOf(Item("a", done = false)),
            kwery.client.getQueryData(ItemsKey),
            "a failed write must be rolled back",
        )
        assertFalse(kwery.client.isOptimistic(ItemsKey))
    }

    // ---- The case snapshot-and-restore gets wrong -------------------------

    @Test
    fun `a failing write does not discard a concurrent one`() = runTest {
        // Snapshot-and-restore is silently wrong here: B snapshots a value that
        // already contains A's optimistic write, so restoring A's snapshot on
        // failure would throw away B's still-pending write.
        val kwery = TestQueryClient(this)
        kwery.seed(Item("a", done = false), Item("b", done = false))

        val failing = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey,
            apply = toggle,
            invalidateOnSettle = false,
        ) { delay(100); throw IllegalStateException("rejected") }

        val succeeding = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey,
            apply = toggle,
            invalidateOnSettle = false,
        ) { delay(1_000) }

        failing.mutate("a")
        kwery.settle(10.milliseconds)
        succeeding.mutate("b")
        kwery.settle(20.milliseconds)

        // Both applied.
        assertEquals(
            listOf(Item("a", done = true), Item("b", done = true)),
            kwery.client.getQueryData(ItemsKey),
        )

        // A fails; B is still in flight.
        kwery.settle(300.milliseconds)
        assertEquals(
            listOf(Item("a", done = false), Item("b", done = true)),
            kwery.client.getQueryData(ItemsKey),
            "A rolled back, but B's pending write must survive",
        )
        assertTrue(kwery.client.isOptimistic(ItemsKey), "B is still in flight")

        kwery.settle(2.seconds)
        assertEquals(
            listOf(Item("a", done = false), Item("b", done = true)),
            kwery.client.getQueryData(ItemsKey),
        )
        assertFalse(kwery.client.isOptimistic(ItemsKey))
    }

    @Test
    fun `two concurrent writes both survive when both succeed`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.seed(Item("a", done = false), Item("b", done = false))

        fun make() = kwery.client
        val first = make().optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey, apply = toggle, invalidateOnSettle = false,
        ) { delay(100) }
        val second = make().optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey, apply = toggle, invalidateOnSettle = false,
        ) { delay(300) }

        first.mutate("a")
        second.mutate("b")
        kwery.settle(1.seconds)

        assertEquals(
            listOf(Item("a", done = true), Item("b", done = true)),
            kwery.client.getQueryData(ItemsKey),
        )
    }

    @Test
    fun `the second write is not mistaken for server truth`() = runTest {
        // If `base` were re-seeded by the second mutation, it would capture the
        // first mutation's optimistic value as if the server had returned it,
        // and rolling both back would leave the first write applied forever.
        val kwery = TestQueryClient(this)
        kwery.seed(Item("a", done = false), Item("b", done = false))

        val failA = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey, apply = toggle, invalidateOnSettle = false,
        ) { delay(200); throw IllegalStateException("no") }
        val failB = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey, apply = toggle, invalidateOnSettle = false,
        ) { delay(400); throw IllegalStateException("no") }

        failA.mutate("a")
        kwery.settle(10.milliseconds)
        failB.mutate("b")
        kwery.settle(2.seconds)

        assertEquals(
            listOf(Item("a", done = false), Item("b", done = false)),
            kwery.client.getQueryData(ItemsKey),
            "both rolled back to genuine server state",
        )
    }

    // ---- Interaction with fetching ---------------------------------------

    @Test
    fun `an in-flight refetch cannot clobber the optimistic value`() = runTest {
        // Step 1 of the choreography. Without cancelQueries, a refetch that
        // resolves after the optimistic write overwrites it, and the UI visibly
        // flicks back to the old value.
        val kwery = TestQueryClient(this)
        kwery.seed(Item("a", done = false))

        // staleTime 0, so attaching starts a refetch straight away. It is
        // still in flight when the optimistic write lands, and would resolve
        // afterwards with server data that predates it.
        val stale = QueryOptions(staleTime = StaleTime.Zero, retry = RetryPolicy.Never)
        val job = backgroundScope.launch {
            kwery.query(ItemsKey, stale) { delay(200); listOf(Item("a", done = false)) }
                .collect { }
        }
        kwery.settle(10.milliseconds)

        val mutation = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey, apply = toggle, invalidateOnSettle = false,
        ) { delay(1_000) }
        mutation.mutate("a")
        kwery.settle(400.milliseconds) // the superseded fetch would have landed by now

        assertEquals(
            listOf(Item("a", done = true)),
            kwery.client.getQueryData(ItemsKey),
            "the superseded fetch must not overwrite the optimistic write",
        )
        job.cancel()
    }

    @Test
    fun `invalidation is deferred until the last write settles`() = runTest {
        // Invalidating when the FIRST write settles would refetch server truth
        // while the second is still pending, clobbering it.
        val kwery = TestQueryClient(this)
        var served = listOf(Item("a", done = false), Item("b", done = false))

        val job = backgroundScope.launch {
            kwery.query(ItemsKey, fresh) { delay(50); served }.collect { }
        }
        kwery.settle(200.milliseconds)
        val afterInitialLoad = kwery.requestCount

        val quick = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey, apply = toggle,
        ) { delay(100) }
        val slow = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey, apply = toggle,
        ) { delay(600) }

        quick.mutate("a")
        slow.mutate("b")
        kwery.settle(300.milliseconds) // quick has settled, slow has not

        assertEquals(
            afterInitialLoad,
            kwery.requestCount,
            "no refetch while another optimistic write is still in flight",
        )

        kwery.settle(1.seconds)
        assertTrue(
            kwery.requestCount > afterInitialLoad,
            "once the last write settles, the cache reconverges on the server",
        )
        job.cancel()
    }

    @Test
    fun `isOptimistic clears even when the mutation fails`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.seed(Item("a", done = false))

        val mutation = kwery.client.optimisticMutation<String, Unit, List<Item>>(
            key = ItemsKey, apply = toggle, invalidateOnSettle = false,
        ) { delay(100); throw IllegalStateException("no") }

        mutation.mutate("a")
        kwery.settle(500.milliseconds)

        assertFalse(kwery.client.isOptimistic(ItemsKey))
        assertFalse(kwery.client.getQueryState(ItemsKey)!!.isOptimistic)
    }
}
