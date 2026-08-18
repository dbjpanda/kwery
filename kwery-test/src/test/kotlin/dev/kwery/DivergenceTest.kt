package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private data class ReportKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("report", id)
}

/**
 * Two divergences from TanStack that the roadmap asserts and nothing proved.
 *
 * Both take the form "Kotlin already gives you this, so Kwery does not add an
 * API for it". That is a fine argument and a bad thing to leave unverified: if
 * the language does *not* in fact cover the case, the missing API is a gap
 * rather than a divergence, and the docs are wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DivergenceTest {

    // ---- Feature 02: no cancellation token ------------------------------

    /**
     * A client in the shape Kwery deliberately does not accommodate: it takes a
     * callback and hands back a handle you must call to abort. This is what
     * `QuerySignal` would have existed for.
     */
    private class CallbackHttpClient {
        val cancelled = AtomicBoolean(false)
        private var deliver: ((String) -> Unit)? = null

        fun enqueue(onResult: (String) -> Unit): () -> Unit {
            deliver = onResult
            return { cancelled.set(true) }
        }

        fun respond(value: String) {
            deliver?.invoke(value)
        }
    }

    @Test
    fun `cancelling a query aborts a callback-based client, with no signal API`() = runTest {
        val kwery = TestQueryClient(this)
        val http = CallbackHttpClient()

        val job = backgroundScope.launch {
            kwery.query(ReportKey("1")) {
                // The bridge TanStack needs a QuerySignal for is fifteen lines
                // of standard library here, and it is the caller's fifteen
                // lines rather than a permanent parameter on every query.
                suspendCancellableCoroutine { continuation ->
                    val abort = http.enqueue { continuation.resume(it) }
                    continuation.invokeOnCancellation { abort() }
                }
            }.collect { }
        }
        kwery.settle()

        assertFalse(http.cancelled.get(), "in flight, nothing aborted yet")

        job.cancel()
        kwery.settle()

        // Not immediately: a request in flight belongs to the entry, not to
        // whichever collector happened to start it. Aborting the moment one
        // screen goes away would kill a request a rotation is about to want
        // back, and would abort a shared fetch out from under a second screen.
        assertFalse(
            http.cancelled.get(),
            "an in-flight request survives a detach — rotation must not abort it",
        )

        // Once the grace window closes and nothing is observing, there is
        // nobody left to want it.
        kwery.settle(10.seconds)

        assertTrue(
            http.cancelled.get(),
            "leaving for good aborted the request — which is the entire job a " +
                "cancellation token would have done",
        )
    }

    @Test
    fun `a callback client that completes normally still delivers`() = runTest {
        val kwery = TestQueryClient(this)
        val http = CallbackHttpClient()
        var last: QueryState<String>? = null

        val job = backgroundScope.launch {
            kwery.query(ReportKey("1")) {
                suspendCancellableCoroutine { continuation ->
                    val abort = http.enqueue { continuation.resume(it) }
                    continuation.invokeOnCancellation { abort() }
                }
            }.collect { last = it }
        }
        kwery.settle()

        http.respond("the report")
        kwery.settle()

        assertEquals("the report", last?.data)
        assertFalse(http.cancelled.get(), "a completed request is not an aborted one")
        job.cancel()
    }

    // ---- Feature 11: no mutation filters --------------------------------

    @Test
    fun `per-mutation status comes from the object, not a filtered global count`() = runTest {
        val kwery = TestQueryClient(this)
        val saveTodo = kwery.client.mutation(
            MutationOptions<String, String, Unit>(mutationFn = { "saved $it" }),
        )
        val deleteTodo = kwery.client.mutation(
            MutationOptions<String, String, Unit>(mutationFn = { "deleted $it" }),
        )

        // TanStack needs useIsMutating({ mutationKey }) because its hooks hand
        // back a fresh object each render and there is nothing stable to hold.
        // Here the mutation IS the handle, so "is this one saving?" is a
        // property read rather than a query against a registry.
        saveTodo.mutate("1")
        kwery.settle()

        assertEquals(MutationStatus.Success, saveTodo.state.value.status)
        assertEquals(MutationStatus.Idle, deleteTodo.state.value.status, "and they do not blur")
        assertEquals(0, kwery.client.isMutating.value, "the global count is for indicators")
    }

    @Test
    fun `two mutations in flight are distinguishable without a filter`() = runTest {
        val kwery = TestQueryClient(this)
        val gateA = kotlinx.coroutines.CompletableDeferred<String>()
        val gateB = kotlinx.coroutines.CompletableDeferred<String>()

        val a = kwery.client.mutation(MutationOptions<Unit, String, Unit>(mutationFn = { gateA.await() }))
        val b = kwery.client.mutation(MutationOptions<Unit, String, Unit>(mutationFn = { gateB.await() }))
        a.mutate(Unit)
        b.mutate(Unit)
        kwery.settle()

        assertEquals(2, kwery.client.isMutating.value)
        assertEquals(MutationStatus.Pending, a.state.value.status)
        assertEquals(MutationStatus.Pending, b.state.value.status)

        gateA.complete("done")
        kwery.settle()

        assertEquals(MutationStatus.Success, a.state.value.status)
        assertEquals(
            MutationStatus.Pending,
            b.state.value.status,
            "each mutation reports itself; no filter needed to tell them apart",
        )
        assertEquals(1, kwery.client.isMutating.value)

        gateB.complete("done")
        kwery.settle()
    }
}
