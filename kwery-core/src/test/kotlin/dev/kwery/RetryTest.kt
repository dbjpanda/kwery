package dev.kwery

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryTest {

    private val error: Throwable = RuntimeException("boom")

    // ---- RetryPolicy -----------------------------------------------------

    @Test
    fun `query default allows exactly three retries`() {
        val policy = RetryPolicy.Default
        assertTrue(policy.shouldRetry(0, error))
        assertTrue(policy.shouldRetry(1, error))
        assertTrue(policy.shouldRetry(2, error))
        assertFalse(policy.shouldRetry(3, error), "the 4th decision must stop")
    }

    @Test
    fun `mutation default allows none`() {
        assertFalse(RetryPolicy.ForMutations.shouldRetry(0, error))
    }

    @Test
    fun `Never and Forever behave as named`() {
        assertFalse(RetryPolicy.Never.shouldRetry(0, error))
        assertTrue(RetryPolicy.Forever.shouldRetry(0, error))
        assertTrue(RetryPolicy.Forever.shouldRetry(1_000_000, error))
    }

    @Test
    fun `Times zero never retries`() {
        assertFalse(RetryPolicy.Times(0).shouldRetry(0, error))
    }

    @Test
    fun `negative retry count is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy.Times(-1) }
    }

    @Test
    fun `Decide receives the failure count and error`() {
        val seen = mutableListOf<Pair<Int, Throwable>>()
        val policy = RetryPolicy.Decide { count, err ->
            seen += count to err
            count < 1
        }
        assertTrue(policy.shouldRetry(0, error))
        assertFalse(policy.shouldRetry(1, error))
        assertEquals(listOf(0 to error, 1 to error), seen)
    }

    // ---- exceptWhen ------------------------------------------------------

    private class HttpError(val code: Int) : RuntimeException("HTTP $code")

    @Test
    fun `exceptWhen blocks matching errors and preserves the base limit`() {
        val policy = RetryPolicy.Times(3).exceptWhen { it is HttpError && it.code in 400..499 }

        assertFalse(policy.shouldRetry(0, HttpError(404)), "4xx must not retry")
        assertTrue(policy.shouldRetry(0, HttpError(503)), "5xx still retries")
        assertTrue(policy.shouldRetry(2, HttpError(503)))
        assertFalse(policy.shouldRetry(3, HttpError(503)), "base limit still applies")
    }

    @Test
    fun `exceptWhen composes`() {
        val policy = RetryPolicy.Forever
            .exceptWhen { it is HttpError && it.code == 404 }
            .exceptWhen { it is IllegalStateException }

        assertFalse(policy.shouldRetry(0, HttpError(404)))
        assertFalse(policy.shouldRetry(0, IllegalStateException()))
        assertTrue(policy.shouldRetry(0, HttpError(500)))
    }

    // ---- RetryDelay ------------------------------------------------------

    @Test
    fun `exponential backoff doubles from one second and caps at thirty`() {
        val delay = RetryDelay.Exponential
        assertEquals(1.seconds, delay.delayFor(0, error))
        assertEquals(2.seconds, delay.delayFor(1, error))
        assertEquals(4.seconds, delay.delayFor(2, error))
        assertEquals(8.seconds, delay.delayFor(3, error))
        assertEquals(16.seconds, delay.delayFor(4, error))
        assertEquals(30.seconds, delay.delayFor(5, error), "capped, not 32s")
        assertEquals(30.seconds, delay.delayFor(6, error))
    }

    @Test
    fun `exponential backoff does not overflow at extreme attempt indices`() {
        // RetryPolicy.Forever can drive this arbitrarily high; `1000L shl 64`
        // wraps rather than saturating, which would produce a negative delay.
        for (attempt in intArrayOf(30, 31, 32, 63, 64, 1000, Int.MAX_VALUE)) {
            val d = RetryDelay.Exponential.delayFor(attempt, error)
            assertEquals(30.seconds, d, "attempt $attempt should saturate at the cap, was $d")
        }
    }

    @Test
    fun `constant delay ignores the attempt index`() {
        val delay = RetryDelay.constant(750.milliseconds)
        assertEquals(750.milliseconds, delay.delayFor(0, error))
        assertEquals(750.milliseconds, delay.delayFor(9, error))
    }

    // ---- Jitter ----------------------------------------------------------

    @Test
    fun `equal jitter stays within half the base and the base`() {
        val jittered = RetryDelay.equalJitter(RetryDelay.Exponential, Random(1234))
        for (attempt in 0..8) {
            val base = RetryDelay.Exponential.delayFor(attempt, error).inWholeMilliseconds
            repeat(50) {
                val actual = jittered.delayFor(attempt, error).inWholeMilliseconds
                assertTrue(
                    actual >= base / 2,
                    "attempt $attempt: $actual below floor ${base / 2}",
                )
                assertTrue(actual <= base, "attempt $attempt: $actual above base $base")
            }
        }
    }

    @Test
    fun `equal jitter never returns zero, unlike full jitter`() {
        // The reason equal jitter was chosen: a near-zero delay would retry
        // almost immediately and defeat the backoff.
        val jittered = RetryDelay.equalJitter(RetryDelay.Exponential, Random(99))
        repeat(200) {
            assertTrue(jittered.delayFor(0, error).inWholeMilliseconds >= 500)
        }
    }

    @Test
    fun `equal jitter actually spreads the delay`() {
        val jittered = RetryDelay.equalJitter(RetryDelay.Exponential, Random(7))
        val observed = (1..200).map { jittered.delayFor(3, error).inWholeMilliseconds }.toSet()
        assertTrue(
            observed.size > 50,
            "jitter should decorrelate retries; saw only ${observed.size} distinct delays",
        )
    }

    @Test
    fun `equal jitter is deterministic for a seeded random`() {
        // Consumers must be able to write deterministic tests against retries.
        fun run() = RetryDelay.equalJitter(RetryDelay.Exponential, Random(42))
            .let { d -> (0..5).map { d.delayFor(it, error) } }

        assertEquals(run(), run())
    }

    @Test
    fun `the default retry delay is jittered`() {
        // Guards the decision itself: if someone swaps Default back to plain
        // Exponential for "parity", this fails.
        val observed = (1..200).map { RetryDelay.Default.delayFor(3, error) }.toSet()
        assertTrue(observed.size > 1, "RetryDelay.Default must apply jitter")
    }
}

class RetryDelayEdgeTest {

    @Test
    fun `a negative attempt index yields no delay rather than a nonsense one`() {
        // delayFor is public API: anyone implementing a custom RetryDelay, or
        // wrapping one, can reach it with whatever index they like. Two's
        // complement makes 1 shl -1 a large positive number, so without the
        // guard a negative index produces a *longer* wait than attempt 0.
        listOf(-1, -5, Int.MIN_VALUE).forEach { attempt ->
            assertEquals(
                Duration.ZERO,
                RetryDelay.Exponential.delayFor(attempt, RuntimeException("x")),
                "attempt $attempt should not wait",
            )
        }
    }

    @Test
    fun `attempt zero is the base delay, and it grows from there`() {
        val zero = RetryDelay.Exponential.delayFor(0, RuntimeException("x"))
        val one = RetryDelay.Exponential.delayFor(1, RuntimeException("x"))
        assertTrue(zero > Duration.ZERO, "the first retry still waits")
        assertTrue(one > zero, "and the second waits longer")
    }
}
