package dev.kwery.persist

import dev.kwery.QueryClient
import dev.kwery.QueryClientConfig
import dev.kwery.QueryOptions
import dev.kwery.RetryPolicy
import dev.kwery.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class NoteKey(val id: String) : PersistableQueryKey<String> {
    override val parts get() = listOf("note", id)
    override val serializer get() = String.serializer()
}

/**
 * How often the cache is written to disk.
 *
 * This is a battery and I/O question, not a correctness one, which is exactly
 * why it needs a test: nothing about the app misbehaves if Kwery writes far more
 * often than it should. It just quietly costs the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersistThrottleTest {

    private fun TestScopeClient(scope: kotlinx.coroutines.test.TestScope): QueryClient =
        QueryClient(
            scope = scope.backgroundScope,
            config = QueryClientConfig(
                timeSource = TimeSource { scope.testScheduler.currentTime },
                defaultQueryOptions = QueryOptions(
                    retry = RetryPolicy.Never,
                    gcTime = 2.hours,
                ),
            ),
        )

    @Test
    fun `a burst of writes inside one window costs a single persist`() = runTest {
        val persister = InMemoryPersister()
        val client = TestScopeClient(this)
        val key = NoteKey("1")

        client.persist(
            backgroundScope,
            PersistOptions(
                persister = persister,
                keys = listOf(key),
                throttle = 1.seconds,
                maxAge = 1.hours,
            ),
        )
        testScheduler.runCurrent()

        repeat(10) { i ->
            client.setQueryData(key) { "v$i" }
        }
        testScheduler.advanceTimeBy(1.seconds.inWholeMilliseconds)
        testScheduler.runCurrent()

        assertEquals(
            1,
            persister.writeCount,
            "ten changes in one throttle window are one write, not ten",
        )
    }

    @Test
    fun `writes in separate windows are separate persists`() = runTest {
        val persister = InMemoryPersister()
        val client = TestScopeClient(this)
        val key = NoteKey("1")

        client.persist(
            backgroundScope,
            PersistOptions(
                persister = persister,
                keys = listOf(key),
                throttle = 1.seconds,
                maxAge = 1.hours,
            ),
        )
        testScheduler.runCurrent()

        repeat(3) { i ->
            client.setQueryData(key) { "v$i" }
            testScheduler.advanceTimeBy(1.seconds.inWholeMilliseconds)
            testScheduler.runCurrent()
        }

        assertEquals(3, persister.writeCount, "one write per window that had a change")
    }

    @Test
    fun `an idle cache is not rewritten every window`() = runTest {
        val persister = InMemoryPersister()
        val client = TestScopeClient(this)
        val key = NoteKey("1")

        client.persist(
            backgroundScope,
            PersistOptions(
                persister = persister,
                keys = listOf(key),
                throttle = 1.seconds,
                maxAge = 1.hours,
            ),
        )
        testScheduler.runCurrent()

        client.setQueryData(key) { "v" }
        testScheduler.advanceTimeBy(1.seconds.inWholeMilliseconds)
        testScheduler.runCurrent()
        val afterChange = persister.writeCount

        // Five minutes with nothing happening. A loop that writes on a timer
        // rather than on a change would write 300 times here — on a phone, for
        // the entire life of the process, for no reason.
        testScheduler.advanceTimeBy(5.minutes.inWholeMilliseconds)
        testScheduler.runCurrent()

        assertEquals(
            afterChange,
            persister.writeCount,
            "an unchanged cache must not be rewritten, was ${persister.writeCount}",
        )
    }

    @Test
    fun `the throttle window is respected under sustained change`() = runTest {
        val persister = InMemoryPersister()
        val client = TestScopeClient(this)
        val key = NoteKey("1")

        client.persist(
            backgroundScope,
            PersistOptions(
                persister = persister,
                keys = listOf(key),
                throttle = 5.seconds,
                maxAge = 1.hours,
            ),
        )
        testScheduler.runCurrent()

        // A change every second for a minute: 60 changes, but at most one write
        // per 5-second window.
        repeat(60) { i ->
            client.setQueryData(key) { "v$i" }
            testScheduler.advanceTimeBy(1.seconds.inWholeMilliseconds)
            testScheduler.runCurrent()
        }

        assertTrue(
            persister.writeCount <= 13,
            "60 changes over 60s with a 5s throttle should be ~12 writes, was ${persister.writeCount}",
        )
    }
}
