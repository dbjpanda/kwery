package dev.kwery.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.RefetchOn
import dev.kwery.QueryState
import dev.kwery.StaleTime
import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class TodoKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("todo", id)
}

class RememberQueryTest {

    @Test
    fun `an equal key across recompositions does not resubscribe`() = runTest {
        val kwery = TestQueryClient(this)
        val requests = AtomicInteger()
        var tick by mutableStateOf(0)
        val composition = TestComposition(backgroundScope) { kwery.settle() }

        composition.setContent {
            CompositionLocalProvider(LocalQueryClient provides kwery.client) {
                // Read `tick` so writing it recomposes this composable, then
                // build a *new but equal* key — exactly what a screen does when
                // an unrelated piece of its state changes.
                val id = if (tick >= 0) "1" else "other"
                rememberQuery(TodoKey(id)) {
                    requests.incrementAndGet()
                    "todo"
                }
            }
        }

        assertEquals(1, requests.get(), "the first composition should fetch once")

        repeat(5) {
            tick++
            composition.frame()
        }

        assertEquals(
            1,
            requests.get(),
            "an equal key is the same subscription — recomposition alone must not refetch",
        )
    }

    @Test
    fun `changing the key resubscribes`() = runTest {
        val kwery = TestQueryClient(this)
        val requested = mutableListOf<String>()
        var id by mutableStateOf("1")
        val composition = TestComposition(backgroundScope) { kwery.settle() }

        composition.setContent {
            CompositionLocalProvider(LocalQueryClient provides kwery.client) {
                rememberQuery(TodoKey(id)) {
                    requested += id
                    "todo-$id"
                }
            }
        }

        assertEquals(listOf("1"), requested)

        id = "2"
        composition.frame()

        assertEquals(
            listOf("1", "2"),
            requested,
            "a different key is different data and must fetch",
        )
    }

    @Test
    fun `a recomposition storm costs no requests, under the most hostile settings`() = runTest {
        // Deliberately the worst case the library permits: no grace window to
        // absorb churn, and refetchOnMount = Always so *any* mount refetches
        // regardless of staleness. If recomposition could produce a redundant
        // request, this configuration would show it.
        //
        // It does not — see the roadmap. No mutation of rememberQuery makes this
        // test fail, because a resubscription hands over between an overlapping
        // pair of collectors and the observer count never reaches zero, so it is
        // never a mount. The guarantee is real and worth locking down; what it
        // is *not* is proof that rememberUpdatedState is load-bearing. The test
        // below, `the latest fetcher is the one that runs`, proves that.
        val kwery = TestQueryClient(this, gracePeriod = Duration.ZERO)
        val requests = AtomicInteger()
        var label by mutableStateOf("a")
        val composition = TestComposition(backgroundScope) { kwery.settle() }

        composition.setContent {
            CompositionLocalProvider(LocalQueryClient provides kwery.client) {
                // Read the state into a local *before* the lambda, so the lambda
                // captures a changing String rather than the stable MutableState.
                // That defeats the Compose compiler's lambda memoisation and
                // produces a genuinely new instance every recomposition — which
                // is what a real call site does: `{ api.todo(id, filter) }`.
                val captured = label
                rememberQuery(
                    TodoKey("1"),
                    // The most sensitive mount detector available: Always
                    // refetches on any mount regardless of staleness.
                    QueryOptions(refetchOnMount = RefetchOn.Always),
                ) {
                    requests.incrementAndGet()
                    "todo-$captured"
                }
            }
        }

        repeat(10) {
            label = "label-$it"
            composition.frame()
        }

        assertEquals(
            1,
            requests.get(),
            "ten recompositions must cost exactly one request",
        )
    }

    @Test
    fun `the latest fetcher is the one that runs`() = runTest {
        val kwery = TestQueryClient(this)
        var label by mutableStateOf("first")
        val seen = mutableListOf<String>()
        val composition = TestComposition(backgroundScope) { kwery.settle() }

        composition.setContent {
            CompositionLocalProvider(LocalQueryClient provides kwery.client) {
                val captured = label
                rememberQuery(
                    TodoKey("1"),
                    QueryOptions(staleTime = StaleTime.Zero, retry = dev.kwery.RetryPolicy.Never),
                ) {
                    seen += captured
                    captured
                }
            }
        }

        assertEquals(listOf("first"), seen)

        // Not resubscribing must not mean running a stale closure: the captured
        // lambda is refreshed even though the subscription is not.
        label = "second"
        composition.frame()
        kwery.client.invalidateQueries(TodoKey("1"))
        kwery.settle()
        composition.frame()

        assertEquals(listOf("first", "second"), seen)
    }

    @Test
    fun `leaving the composition detaches the observer`() = runTest {
        val kwery = TestQueryClient(this)
        val requests = AtomicInteger()
        val composition = TestComposition(backgroundScope) { kwery.settle() }

        composition.setContent {
            CompositionLocalProvider(LocalQueryClient provides kwery.client) {
                rememberQuery(TodoKey("1")) {
                    requests.incrementAndGet()
                    "todo"
                }
            }
        }
        assertEquals(1, requests.get())

        composition.dispose()

        // Past the grace window and the gc timer: nothing is observing, so the
        // entry goes and no further work happens on its behalf.
        kwery.settle(10.seconds)
        kwery.settle(6.minutes)

        assertEquals(1, requests.get(), "a disposed composition must not keep fetching")
    }

    @Test
    fun `remounting inside the grace window does not refetch`() = runTest {
        val kwery = TestQueryClient(this)
        val requests = AtomicInteger()

        fun mount(): TestComposition {
            val c = TestComposition(backgroundScope) { kwery.settle() }
            c.setContent {
                CompositionLocalProvider(LocalQueryClient provides kwery.client) {
                    rememberQuery(TodoKey("1")) {
                        requests.incrementAndGet()
                        "todo"
                    }
                }
            }
            return c
        }

        val first = mount()
        assertEquals(1, requests.get())

        // A rotation: the composition is torn down and rebuilt immediately.
        // With the default staleTime of zero, treating this as a fresh mount
        // would refetch every single time the device turns.
        first.dispose()
        kwery.settle(1.seconds)
        val second = mount()

        assertEquals(
            1,
            requests.get(),
            "a remount inside the grace window is a continuation, not a new mount",
        )
        second.dispose()
    }

    @Test
    fun `the state reaching the composable carries the loaded data`() = runTest {
        val kwery = TestQueryClient(this)
        var last: QueryState<String>? = null
        val composition = TestComposition(backgroundScope) { kwery.settle() }

        composition.setContent {
            CompositionLocalProvider(LocalQueryClient provides kwery.client) {
                last = rememberQuery(TodoKey("1")) { "todo" }
            }
        }
        composition.frame()

        val state = requireNotNull(last)
        assertEquals("todo", state.data)
        assertTrue(state.isSuccess)
    }
}


