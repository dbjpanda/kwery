package dev.kwery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scope-lock map must not grow for the life of the process.
 *
 * Nothing misbehaves if it does — every mutation still serialises correctly —
 * which is exactly why this needs a test that counts something rather than one
 * that checks a result. Per-entity scopes (`"todo-$id"`) are a natural way to
 * serialise edits to one item, and there is no bound on how many ids an app
 * touches over days of uptime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MutationLockLifetimeTest {

    private fun options(scopeId: String) = MutationOptions<Unit, String, Unit>(
        mutationFn = { "ok" },
        scope = MutationScope(scopeId),
    )

    @Test
    fun `mutations sharing a scope share one lock`() = runTest {
        val client = QueryClient(backgroundScope, QueryClientConfig())

        // Held strongly for the duration: the locks must not be collected while
        // a mutation that uses them is alive.
        val held = List(50) { client.mutation(options("same")) }

        assertEquals(1, client.mutationLockCount(), "one scope is one lock")
        assertTrue(held.isNotEmpty())
        client.close()
    }

    @Test
    fun `distinct scopes held at once each get a lock`() = runTest {
        val client = QueryClient(backgroundScope, QueryClientConfig())
        val held = List(20) { client.mutation(options("todo-$it")) }

        assertEquals(20, client.mutationLockCount())
        assertTrue(held.isNotEmpty())
        client.close()
    }

    @Test
    fun `locks for scopes no longer in use do not accumulate`() = runTest {
        val client = QueryClient(backgroundScope, QueryClientConfig())

        // Ten thousand one-shot per-entity scopes, none retained — an app
        // editing items over a long session. A strong map keeps every one.
        repeat(10_000) { i ->
            client.mutation(options("todo-$i"))
        }

        // Weak references clear at the JVM's discretion, so ask, then check,
        // rather than assuming a single gc() call is enough.
        var live = client.mutationLockCount()
        repeat(20) {
            if (live < 1_000) return@repeat
            @Suppress("ExplicitGarbageCollectionCall")
            System.gc()
            Thread.sleep(10)
            client.mutation(options("prune-trigger-$it"))   // pruning runs on insert
            live = client.mutationLockCount()
        }

        assertTrue(
            live < 1_000,
            "the map should not retain ten thousand dead locks, still holds $live",
        )
        client.close()
    }
}
