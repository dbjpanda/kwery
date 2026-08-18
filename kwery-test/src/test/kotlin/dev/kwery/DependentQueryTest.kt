package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class UserKey(val email: String) : QueryKey<String> {
    override val parts get() = listOf("user", email)
}

private data class ProjectsKey(val userId: String) : QueryKey<List<String>> {
    override val parts get() = listOf("projects", userId)
}

/**
 * Dependent queries — one query needing another's result — and the parallel
 * case, which in Kotlin needs no library support at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DependentQueryTest {

    @Test
    fun `a dependent query does not fire until its dependency resolves, then fires once`() =
        runTest {
            val kwery = TestQueryClient(this)
            val order = mutableListOf<String>()

            val job = backgroundScope.launch {
                kwery.query(UserKey("a@b.c")) { order += "user"; "user-7" }
                    .flatMapLatest { userState ->
                        val id = userState.data
                        if (id == null) {
                            flowOf(QueryState())
                        } else {
                            kwery.query(ProjectsKey(id)) { order += "projects"; listOf("p1") }
                        }
                    }
                    .collect { }
            }
            kwery.awaitIdle()

            assertEquals(
                listOf("user", "projects"),
                order,
                "strictly sequential — that is the waterfall, and it is the cost",
            )
            assertEquals(1, kwery.requestCountFor(ProjectsKey("user-7")), "and it fires once")

            job.cancel()
        }

    @Test
    fun `a dependent query gated by enabled never fires while the input is missing`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0

        val job = backgroundScope.launch {
            kwery.query(
                ProjectsKey("unknown"),
                QueryOptions(enabled = false),
            ) { attempts++; listOf("p1") }.collect { }
        }
        kwery.settle(1.minutes)

        assertEquals(0, attempts, "a disabled query is not a slow query — it never runs")
        job.cancel()
    }

    @Test
    fun `parallel queries run concurrently rather than in sequence`() = runTest {
        val kwery = TestQueryClient(this)
        val started = mutableListOf<String>()

        val job = backgroundScope.launch {
            combine(
                (1..5).map { i ->
                    kwery.query(ProjectsKey("u$i")) {
                        started += "u$i"
                        kotlinx.coroutines.delay(1.seconds)
                        listOf("p$i")
                    }
                },
            ) { it.toList() }.collect { }
        }

        // All five start before any finishes. Sequential execution would show
        // one name here, not five.
        kwery.settle()
        assertEquals(5, started.size, "all five started before any completed, saw $started")

        kwery.awaitIdle()
        assertEquals(5, kwery.requestCount)
        job.cancel()
    }

    @Test
    fun `a dependency that changes cancels the old dependent query`() = runTest {
        val kwery = TestQueryClient(this)
        val users = kotlinx.coroutines.flow.MutableStateFlow("a@b.c")

        val job = backgroundScope.launch {
            users.flatMapLatest { email ->
                kwery.query(UserKey(email)) { "id-${email.first()}" }
                    .flatMapLatest { state ->
                        val id = state.data
                        if (id == null) flowOf(QueryState())
                        else kwery.query(ProjectsKey(id)) { listOf(id) }
                    }
            }.collect { }
        }
        kwery.awaitIdle()
        val afterFirst = kwery.requestCount

        users.value = "z@b.c"
        kwery.awaitIdle()

        // Two new requests: the new user, and its projects. Nothing re-runs for
        // the abandoned branch.
        assertEquals(afterFirst + 2, kwery.requestCount, "saw ${kwery.recordedRequests}")
        assertTrue(kwery.recordedRequests.count { it == UserKey("a@b.c") } == 1)

        job.cancel()
    }
}
