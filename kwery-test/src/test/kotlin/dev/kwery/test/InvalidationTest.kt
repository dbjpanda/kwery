package dev.kwery.test

import dev.kwery.FetchStatus
import dev.kwery.QueryFilters
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryType
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import dev.kwery.prefixOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class ProjectKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("projects", id)
}

private data class UserKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("users", id)
}

/** Feature 08 — invalidation and filters. */
class InvalidationTest {

    private val fresh = QueryOptions(staleTime = StaleTime.of(5.minutes), retry = RetryPolicy.Never)

    @Test
    fun `invalidation refetches active queries and leaves inactive ones merely stale`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 1.seconds)
        val active = ProjectKey("active")
        val inactive = ProjectKey("inactive")

        val activeJob = backgroundScope.launch {
            kwery.query(active, fresh) { delay(50); "data" }.collect { }
        }
        val inactiveJob = backgroundScope.launch {
            kwery.query(inactive, fresh) { delay(50); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)
        inactiveJob.cancel()
        kwery.settle(2.seconds) // past grace: now genuinely inactive
        kwery.clearRecordedRequests()

        kwery.client.invalidateQueries(prefixOf("projects"))
        kwery.settle(300.milliseconds)

        assertEquals(
            listOf(active),
            kwery.recordedRequests,
            "only the observed query refetches; the other waits to be observed again",
        )
        assertTrue(
            kwery.client.cacheSnapshot().single { it.key == inactive }.isStale,
            "but the inactive one IS marked stale",
        )
        activeJob.cancel()
    }

    @Test
    fun `an invalidated inactive query refetches when observed again`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 1.seconds)
        val key = ProjectKey("later")

        val first = backgroundScope.launch {
            kwery.query(key, fresh) { delay(50); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)
        first.cancel()
        kwery.settle(2.seconds)

        kwery.client.invalidateQueries(key)
        kwery.settle(100.milliseconds)
        kwery.clearRecordedRequests()

        val second = backgroundScope.launch {
            kwery.query(key, fresh) { delay(50); "data" }.collect { }
        }
        kwery.settle(300.milliseconds)

        assertEquals(1, kwery.requestCount, "the deferred refetch happens on reattach")
        second.cancel()
    }

    @Test
    fun `awaiting invalidateQueries waits for the refetch to settle`() = runTest {
        // What lets a mutation stay Pending until the list has actually
        // refreshed, instead of flashing "done" first.
        val kwery = TestQueryClient(this)
        val key = ProjectKey("await")
        val job = backgroundScope.launch {
            kwery.query(key, fresh) { delay(300); "data" }.collect { }
        }
        kwery.settle(500.milliseconds)

        var finished = false
        backgroundScope.launch {
            kwery.client.invalidateQueries(key)
            finished = true
        }
        kwery.settle(50.milliseconds)
        assertTrue(!finished, "still refetching")

        kwery.settle(500.milliseconds)
        assertTrue(finished, "and completes once the refetch settles")
        job.cancel()
    }

    @Test
    fun `prefix matching selects a family without touching its neighbours`() = runTest {
        val kwery = TestQueryClient(this)
        val project = ProjectKey("1")
        val user = UserKey("1")

        val jobs = listOf(project to "p", user to "u").map { (key, value) ->
            backgroundScope.launch { kwery.query(key, fresh) { delay(50); value }.collect { } }
        }
        kwery.settle(200.milliseconds)
        kwery.clearRecordedRequests()

        kwery.client.invalidateQueries(prefixOf("projects"))
        kwery.settle(300.milliseconds)

        assertEquals(listOf(project), kwery.recordedRequests)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `the type filter distinguishes active from inactive`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 1.seconds)
        val active = ProjectKey("a")
        val inactive = ProjectKey("b")

        val activeJob = backgroundScope.launch {
            kwery.query(active, fresh) { delay(50); "data" }.collect { }
        }
        val inactiveJob = backgroundScope.launch {
            kwery.query(inactive, fresh) { delay(50); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)
        inactiveJob.cancel()
        kwery.settle(2.seconds)
        kwery.clearRecordedRequests()

        kwery.client.refetchQueries(QueryFilters(type = QueryType.Active))
        kwery.settle(300.milliseconds)

        assertEquals(listOf(active), kwery.recordedRequests)
        activeJob.cancel()
    }

    @Test
    fun `a predicate filter narrows further`() = runTest {
        val kwery = TestQueryClient(this)
        val jobs = listOf("keep", "skip").map { id ->
            backgroundScope.launch {
                kwery.query(ProjectKey(id), fresh) { delay(50); id }.collect { }
            }
        }
        kwery.settle(200.milliseconds)
        kwery.clearRecordedRequests()

        kwery.client.refetchQueries(
            QueryFilters(
                keyPrefix = listOf("projects"),
                predicate = { (it.key as ProjectKey).id == "keep" },
            ),
        )
        kwery.settle(300.milliseconds)

        assertEquals(listOf(ProjectKey("keep")), kwery.recordedRequests)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `removeQueries evicts outright without refetching`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ProjectKey("gone")
        val job = backgroundScope.launch {
            kwery.query(key, fresh) { delay(50); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)
        kwery.clearRecordedRequests()

        kwery.client.removeQueries(QueryFilters(exactKey = key))
        kwery.settle(200.milliseconds)

        assertEquals(0, kwery.requestCount, "removal is not an invalidation")
        job.cancel()
    }

    @Test
    fun `resetQueries returns an entry to its initial state`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ProjectKey("reset")
        val job = backgroundScope.launch {
            kwery.query(key, fresh) { delay(50); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)
        assertEquals("data", kwery.client.getQueryData(key))

        kwery.client.resetQueries(QueryFilters(exactKey = key))
        kwery.settle(50.milliseconds)

        assertEquals(null, kwery.client.getQueryData(key))
        job.cancel()
    }

    @Test
    fun `cancelQueries stops a fetch without marking it failed`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ProjectKey("cancel")
        val job = backgroundScope.launch {
            kwery.query(key, fresh) { delay(5_000); "data" }.collect { }
        }
        kwery.settle(100.milliseconds)

        kwery.client.cancelQueries(QueryFilters.All)
        kwery.settle(200.milliseconds)

        val state = kwery.client.getQueryState(key)!!
        assertEquals(FetchStatus.Idle, state.fetchStatus)
        assertEquals(null, state.error, "a cancelled fetch is not a failed one")
        job.cancel()
    }
}
