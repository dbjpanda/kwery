package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The global mutation counter behind `useIsMutating`'s Kwery equivalent.
 *
 * Every assertion here is about the *count*, not about any one mutation's
 * state: a global indicator is wrong if it is right about each mutation
 * individually but wrong about how many are outstanding.
 */
class IsMutatingTest {

    @Test
    fun `isMutating counts concurrent mutations and returns to zero`() = runTest {
        val kwery = TestQueryClient(this)
        val client = kwery.client
        val gateA = CompletableDeferred<String>()
        val gateB = CompletableDeferred<String>()

        assertEquals(0, client.isMutating.value)

        val a = client.mutation(MutationOptions<Unit, String, Unit>(mutationFn = { gateA.await() }))
        val b = client.mutation(MutationOptions<Unit, String, Unit>(mutationFn = { gateB.await() }))

        a.mutate(Unit)
        kwery.settle()
        assertEquals(1, client.isMutating.value)

        b.mutate(Unit)
        kwery.settle()
        assertEquals(2, client.isMutating.value, "two independent writes are two")

        gateA.complete("a")
        kwery.settle()
        assertEquals(1, client.isMutating.value)

        gateB.complete("b")
        kwery.settle()
        assertEquals(0, client.isMutating.value, "the counter must come back down")
    }

    @Test
    fun `a mutation queued behind its scope still counts`() = runTest {
        val kwery = TestQueryClient(this)
        val client = kwery.client
        val gate = CompletableDeferred<String>()
        val scope = MutationScope("uploads")

        val first = client.mutation(
            MutationOptions<Unit, String, Unit>(mutationFn = { gate.await() }, scope = scope),
        )
        val second = client.mutation(
            MutationOptions<Unit, String, Unit>(mutationFn = { "second" }, scope = scope),
        )

        first.mutate(Unit)
        kwery.settle()
        second.mutate(Unit)
        kwery.settle()

        // The second is blocked on the scope lock and has not run its
        // mutationFn. It has still been submitted, and will happen — counting
        // it as idle would let a save button re-enable between two writes the
        // user submitted back to back.
        assertEquals(2, client.isMutating.value, "a queued write is still outstanding")

        gate.complete("first")
        kwery.settle()
        assertEquals(0, client.isMutating.value)
    }

    @Test
    fun `a failed mutation decrements`() = runTest {
        val kwery = TestQueryClient(this)
        val client = kwery.client

        val failing = client.mutation(
            MutationOptions<Unit, String, Unit>(
                mutationFn = { error("nope") },
                retry = RetryPolicy.Never,
            ),
        )
        failing.mutate(Unit)
        kwery.settle()

        assertEquals(0, client.isMutating.value, "a failure must not leak a count")
    }

    @Test
    fun `a cancelled mutation decrements`() = runTest {
        val kwery = TestQueryClient(this)
        val client = kwery.client
        val gate = CompletableDeferred<String>()

        val slow = client.mutation(MutationOptions<Unit, String, Unit>(mutationFn = { gate.await() }))
        val job = slow.mutate(Unit)
        kwery.settle()
        assertEquals(1, client.isMutating.value)

        // The decrement is in a finally that does not suspend, which is what
        // makes it survive cancellation. A suspending cleanup would not run.
        job.cancel()
        kwery.settle()

        assertEquals(0, client.isMutating.value, "cancellation must not leak a count")
    }

    @Test
    fun `mutateAwait is counted too`() = runTest {
        val kwery = TestQueryClient(this)
        val client = kwery.client
        val gate = CompletableDeferred<String>()
        val m = client.mutation(MutationOptions<Unit, String, Unit>(mutationFn = { gate.await() }))

        val job = backgroundScope.launch { m.mutateAwait(Unit) }
        kwery.settle()
        assertEquals(1, client.isMutating.value, "the awaiting path shares the counter")

        gate.complete("done")
        kwery.settle()
        assertEquals(0, client.isMutating.value)
        job.cancel()
    }
}
