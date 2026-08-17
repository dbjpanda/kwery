package dev.kwery.test

import dev.kwery.MutationOptions
import dev.kwery.MutationScope
import dev.kwery.MutationStatus
import dev.kwery.NetworkMode
import dev.kwery.RetryPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Feature 11 — mutations. */
class MutationTest {

    // ---- Lifecycle callbacks --------------------------------------------

    @Test
    fun `callbacks fire in order on success`() = runTest {
        val kwery = TestQueryClient(this)
        val order = mutableListOf<String>()

        val mutation = kwery.client.mutation(
            MutationOptions<String, String, Unit>(
                mutationFn = { input -> delay(50); "result:$input" },
                onMutate = { order += "onMutate" },
                onSuccess = { _, _, _ -> order += "onSuccess" },
                onError = { _, _, _ -> order += "onError" },
                onSettled = { _, _, _, _ -> order += "onSettled" },
            ),
        )

        mutation.mutate("x")
        kwery.settle(200.milliseconds)

        assertEquals(listOf("onMutate", "onSuccess", "onSettled"), order)
    }

    @Test
    fun `callbacks fire in order on failure`() = runTest {
        val kwery = TestQueryClient(this)
        val order = mutableListOf<String>()

        val mutation = kwery.client.mutation(
            MutationOptions<String, String, Unit>(
                mutationFn = { delay(50); throw IllegalStateException("boom") },
                onMutate = { order += "onMutate" },
                onSuccess = { _, _, _ -> order += "onSuccess" },
                onError = { _, _, _ -> order += "onError" },
                onSettled = { _, _, _, _ -> order += "onSettled" },
            ),
        )

        mutation.mutate("x")
        kwery.settle(200.milliseconds)

        assertEquals(listOf("onMutate", "onError", "onSettled"), order)
    }

    @Test
    fun `the onMutate context reaches onError and onSettled, typed`() = runTest {
        val kwery = TestQueryClient(this)
        var errorContext: List<String>? = null
        var settledContext: List<String>? = null

        // The rollback channel: a typed snapshot, with no cast at the use site.
        val mutation = kwery.client.mutation(
            MutationOptions<String, String, List<String>>(
                mutationFn = { delay(50); throw IllegalStateException("boom") },
                onMutate = { listOf("snapshot", "of", "previous") },
                onError = { _, _, context -> errorContext = context },
                onSettled = { _, _, _, context -> settledContext = context },
            ),
        )

        mutation.mutate("x")
        kwery.settle(200.milliseconds)

        assertEquals(listOf("snapshot", "of", "previous"), errorContext)
        assertEquals(listOf("snapshot", "of", "previous"), settledContext)
    }

    @Test
    fun `onSettled receives data on success and error on failure`() = runTest {
        val kwery = TestQueryClient(this)
        var successArgs: Pair<String?, Throwable?>? = null
        var failureArgs: Pair<String?, Throwable?>? = null

        kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { delay(10); "ok" },
                onSettled = { data, error, _, _ -> successArgs = data to error },
            ),
        ).mutate(1)
        kwery.settle(100.milliseconds)

        kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { delay(10); throw IllegalStateException("nope") },
                onSettled = { data, error, _, _ -> failureArgs = data to error },
            ),
        ).mutate(1)
        kwery.settle(100.milliseconds)

        assertEquals("ok", successArgs?.first)
        assertNull(successArgs?.second)
        assertNull(failureArgs?.first)
        assertNotNull(failureArgs?.second)
    }

    @Test
    fun `the mutation stays Pending until onSettled completes`() = runTest {
        // This is what lets `onSettled = { invalidateQueries(...) }` keep a
        // button disabled until the list has actually refreshed.
        val kwery = TestQueryClient(this)
        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { delay(50); "done" },
                onSettled = { _, _, _, _ -> delay(500) },
            ),
        )

        mutation.mutate(1)
        kwery.settle(200.milliseconds) // fn finished, onSettled still running
        assertEquals(MutationStatus.Pending, mutation.state.value.status)

        kwery.settle(500.milliseconds)
        assertEquals(MutationStatus.Success, mutation.state.value.status)
    }

    // ---- Status and variables -------------------------------------------

    @Test
    fun `a mutation starts Idle, unlike a query`() = runTest {
        val kwery = TestQueryClient(this)
        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(mutationFn = { "x" }),
        )
        assertTrue(mutation.state.value.isIdle)
        assertNull(mutation.state.value.variables)
    }

    @Test
    fun `variables are exposed while pending and retained after an error`() = runTest {
        val kwery = TestQueryClient(this)
        val mutation = kwery.client.mutation(
            MutationOptions<String, String, Unit>(
                mutationFn = { delay(50); throw IllegalStateException("boom") },
            ),
        )

        mutation.mutate("the input")
        kwery.settle(10.milliseconds)
        assertEquals("the input", mutation.state.value.variables)
        assertNotNull(mutation.state.value.submittedAt)

        kwery.settle(200.milliseconds)
        assertTrue(mutation.state.value.isError)
        assertEquals(
            "the input",
            mutation.state.value.variables,
            "variables must survive an error so a retry button can reuse them",
        )
    }

    @Test
    fun `reset returns the mutation to Idle`() = runTest {
        val kwery = TestQueryClient(this)
        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(mutationFn = { delay(10); "x" }),
        )

        mutation.mutate(1)
        kwery.settle(100.milliseconds)
        assertTrue(mutation.state.value.isSuccess)

        mutation.reset()
        assertTrue(mutation.state.value.isIdle)
        assertNull(mutation.state.value.data)
        assertNull(mutation.state.value.variables)
    }

    // ---- mutate vs mutateAwait ------------------------------------------

    @Test
    fun `mutateAwait returns the result`() = runTest {
        val kwery = TestQueryClient(this)
        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(mutationFn = { n -> delay(10); "n=$n" }),
        )

        var result: String? = null
        backgroundScope.launch { result = mutation.mutateAwait(7) }
        kwery.settle(100.milliseconds)

        assertEquals("n=7", result)
    }

    @Test
    fun `mutateAwait throws the original exception instance`() = runTest {
        val kwery = TestQueryClient(this)
        val boom = IllegalStateException("boom")
        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(mutationFn = { delay(10); throw boom }),
        )

        var caught: Throwable? = null
        backgroundScope.launch {
            caught = assertFailsWith<IllegalStateException> { mutation.mutateAwait(1) }
        }
        kwery.settle(100.milliseconds)

        assertSame(boom, caught, "the caller must get their own exception back")
    }

    @Test
    fun `mutate does not throw on failure`() = runTest {
        // Fire-and-forget: failures surface through state, and must not take
        // down the client's scope.
        val kwery = TestQueryClient(this)
        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { delay(10); throw IllegalStateException("boom") },
            ),
        )

        mutation.mutate(1)
        kwery.settle(100.milliseconds)

        assertTrue(mutation.state.value.isError)

        // The client is still usable afterwards.
        val second = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(mutationFn = { delay(10); "fine" }),
        )
        second.mutate(1)
        kwery.settle(100.milliseconds)
        assertTrue(second.state.value.isSuccess)
    }

    // ---- Retry ------------------------------------------------------------

    @Test
    fun `mutations do not retry by default`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0
        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { attempts++; delay(10); throw IllegalStateException("boom") },
            ),
        )

        mutation.mutate(1)
        kwery.settle(5.seconds)

        assertEquals(1, attempts, "a retried non-idempotent write can charge twice")
    }

    @Test
    fun `retry can be opted into`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0
        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = {
                    attempts++
                    delay(10)
                    if (attempts < 3) throw IllegalStateException("boom") else "ok"
                },
                retry = RetryPolicy.Times(3),
            ),
        )

        mutation.mutate(1)
        kwery.settle(60.seconds)

        assertEquals(3, attempts)
        assertTrue(mutation.state.value.isSuccess)
    }

    // ---- Scopes -----------------------------------------------------------

    @Test
    fun `mutations sharing a scope run serially and in order`() = runTest {
        val kwery = TestQueryClient(this)
        val events = mutableListOf<String>()
        val scope = MutationScope("todo")

        fun make(label: String) = MutationOptions<Int, String, Unit>(
            mutationFn = {
                events += "start:$label"
                delay(100)
                events += "end:$label"
                label
            },
            scope = scope,
        )

        val a = kwery.client.mutation(make("a"))
        val b = kwery.client.mutation(make("b"))
        val c = kwery.client.mutation(make("c"))

        a.mutate(1); b.mutate(1); c.mutate(1)
        kwery.settle(1.seconds)

        assertEquals(
            listOf("start:a", "end:a", "start:b", "end:b", "start:c", "end:c"),
            events,
            "scoped mutations must not interleave",
        )
    }

    @Test
    fun `a queued scoped mutation reports isPaused`() = runTest {
        val kwery = TestQueryClient(this)
        val scope = MutationScope("todo")

        val first = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(mutationFn = { delay(500); "a" }, scope = scope),
        )
        val second = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(mutationFn = { delay(500); "b" }, scope = scope),
        )

        first.mutate(1)
        kwery.settle(10.milliseconds)
        second.mutate(1)
        kwery.settle(50.milliseconds)

        assertTrue(second.state.value.isPaused, "a queued mutation is paused, not running")
        assertFalse(first.state.value.isPaused)

        kwery.settle(2.seconds)
        assertTrue(second.state.value.isSuccess)
        assertFalse(second.state.value.isPaused)
    }

    @Test
    fun `different scopes run concurrently`() = runTest {
        val kwery = TestQueryClient(this)
        val events = mutableListOf<String>()

        fun make(label: String, scopeId: String) = MutationOptions<Int, String, Unit>(
            mutationFn = { events += "start:$label"; delay(100); events += "end:$label"; label },
            scope = MutationScope(scopeId),
        )

        kwery.client.mutation(make("a", "one")).mutate(1)
        kwery.client.mutation(make("b", "two")).mutate(1)
        kwery.settle(50.milliseconds)

        assertEquals(
            listOf("start:a", "start:b"),
            events,
            "independent scopes must not block each other",
        )
    }

    @Test
    fun `a failing scoped mutation does not block the queue`() = runTest {
        val kwery = TestQueryClient(this)
        val scope = MutationScope("todo")

        val failing = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { delay(50); throw IllegalStateException("boom") },
                scope = scope,
            ),
        )
        val following = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(mutationFn = { delay(50); "ok" }, scope = scope),
        )

        failing.mutate(1)
        following.mutate(1)
        kwery.settle(1.seconds)

        assertTrue(failing.state.value.isError)
        assertTrue(following.state.value.isSuccess, "the scope lock must be released on failure")
    }

    // ---- Offline ----------------------------------------------------------

    @Test
    fun `an offline mutation pauses instead of failing`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        var attempts = 0
        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { attempts++; delay(50); "ok" },
            ),
        )

        mutation.mutate(1)
        kwery.settle(500.milliseconds)

        assertTrue(mutation.state.value.isPaused)
        assertEquals(MutationStatus.Pending, mutation.state.value.status)
        assertEquals(0, attempts, "the write must not be attempted while offline")

        kwery.setOnline(true)
        kwery.settle(500.milliseconds)

        assertTrue(mutation.state.value.isSuccess)
        assertEquals(1, attempts)
    }

    @Test
    fun `NetworkMode Always runs a mutation while offline`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { delay(50); "ok" },
                networkMode = NetworkMode.Always,
            ),
        )

        mutation.mutate(1)
        kwery.settle(500.milliseconds)

        assertTrue(mutation.state.value.isSuccess)
    }

    @Test
    fun `onMutate runs before the mutation waits for its turn`() = runTest {
        // An optimistic update must be visible immediately, not after the
        // queue drains — otherwise the UI lags behind the user's tap.
        val kwery = TestQueryClient(this)
        val scope = MutationScope("todo")
        val events = mutableListOf<String>()

        val blocking = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(mutationFn = { delay(500); "a" }, scope = scope),
        )
        val queued = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { delay(50); "b" },
                onMutate = { events += "queued:onMutate" },
                scope = scope,
            ),
        )

        blocking.mutate(1)
        kwery.settle(10.milliseconds)
        queued.mutate(1)
        kwery.settle(50.milliseconds)

        assertEquals(
            listOf("queued:onMutate"),
            events,
            "onMutate must not wait for the scope lock",
        )
    }

    // ---- Throwing callbacks (ported from TanStack's cascade tests) --------

    @Test
    fun `a throwing onSettled on the success path promotes the mutation to Error`() = runTest {
        // Ported from "error by global onSettled triggers onError callback".
        // The mutationFn succeeded, but a callback failed, so the mutation as a
        // whole did not: onError runs even though nothing about the write went
        // wrong, and onSettled is entered a second time.
        val kwery = TestQueryClient(this)
        val order = mutableListOf<String>()
        var settledCount = 0

        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { delay(10); "ok" },
                onSuccess = { _, _, _ -> order += "onSuccess" },
                onError = { _, _, _ -> order += "onError" },
                onSettled = { _, _, _, _ ->
                    settledCount++
                    order += "onSettled"
                    if (settledCount == 1) throw IllegalStateException("callback blew up")
                },
            ),
        )

        mutation.mutate(1)
        kwery.settle(200.milliseconds)

        assertEquals(listOf("onSuccess", "onSettled", "onError", "onSettled"), order)
        assertTrue(mutation.state.value.isError)
    }

    @Test
    fun `a throwing onError does not replace the original failure`() = runTest {
        // TanStack routes a throwing onError to an unhandled-rejection channel,
        // which loses it. Kwery keeps the original error primary and attaches
        // the callback failure as suppressed, so neither is lost.
        val kwery = TestQueryClient(this)
        val original = IllegalStateException("the real failure")
        val fromCallback = IllegalArgumentException("callback blew up")

        val mutation = kwery.client.mutation(
            MutationOptions<Int, String, Unit>(
                mutationFn = { delay(10); throw original },
                onError = { _, _, _ -> throw fromCallback },
            ),
        )

        var caught: Throwable? = null
        backgroundScope.launch {
            caught = assertFailsWith<IllegalStateException> { mutation.mutateAwait(1) }
        }
        kwery.settle(200.milliseconds)

        assertSame(original, caught, "the caller must see the real failure")
        assertTrue(
            caught!!.suppressed.any { it === fromCallback },
            "the callback failure must be preserved, not silently dropped",
        )
        assertSame(original, mutation.state.value.error)
    }
}
