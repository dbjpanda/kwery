package dev.kwery

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How many times a failed query is retried.
 *
 * Queries default to [Default] (three retries); mutations default to
 * [ForMutations] (none). That asymmetry is deliberate and matches TanStack: a
 * retried non-idempotent write can charge a customer twice.
 *
 * A [kotlinx.coroutines.CancellationException] is **never** retried and never
 * counts toward the failure count, whatever policy is set. That is enforced by
 * the retry engine rather than left to user predicates, because getting it
 * wrong turns every screen exit into a retry storm.
 */
public sealed interface RetryPolicy {

    /** Fail on the first error. */
    public data object Never : RetryPolicy

    /** Retry up to [count] times before surfacing the error. */
    public data class Times(val count: Int) : RetryPolicy {
        init {
            require(count >= 0) { "retry count must be >= 0, was $count" }
        }
    }

    /** Retry indefinitely. Pair with a bounded [RetryDelay]. */
    public data object Forever : RetryPolicy

    /** Decide per failure. */
    public fun interface Decide : RetryPolicy {
        /**
         * @param failureCount failures so far; **0 for the first retry
         *   decision**, matching TanStack.
         */
        public fun decide(failureCount: Int, error: Throwable): Boolean
    }

    public companion object {
        /** Three retries — the query default. */
        public val Default: RetryPolicy = Times(3)

        /** No retries — the mutation default. */
        public val ForMutations: RetryPolicy = Never
    }
}

/**
 * Whether another attempt should be made after [failureCount] failures.
 */
public fun RetryPolicy.shouldRetry(failureCount: Int, error: Throwable): Boolean = when (this) {
    RetryPolicy.Never -> false
    RetryPolicy.Forever -> true
    is RetryPolicy.Times -> failureCount < count
    is RetryPolicy.Decide -> decide(failureCount, error)
}

/**
 * Narrow this policy so errors matching [predicate] are never retried.
 *
 * Retrying a 4xx is pointless — the request will fail identically every time —
 * so nearly every real codebase writes this predicate by hand. Kwery ships it:
 *
 * ```kotlin
 * RetryPolicy.Times(3).exceptWhen { it is HttpException && it.code in 400..499 }
 * ```
 */
public fun RetryPolicy.exceptWhen(predicate: (Throwable) -> Boolean): RetryPolicy {
    val base = this
    return RetryPolicy.Decide { failureCount, error ->
        !predicate(error) && base.shouldRetry(failureCount, error)
    }
}

/**
 * How long to wait before retry attempt [attemptIndex] (0-based).
 */
public fun interface RetryDelay {
    public fun delayFor(attemptIndex: Int, error: Throwable): Duration

    public companion object {
        /** Longest backoff Kwery will wait, matching TanStack's cap. */
        public val MaxBackoff: Duration = 30.seconds

        /**
         * `min(1s * 2^attempt, 30s)` — exactly TanStack's timing, with no
         * jitter. Available for strict parity; [Default] is what queries use.
         */
        public val Exponential: RetryDelay = RetryDelay { attemptIndex, _ ->
            exponentialBackoff(attemptIndex)
        }

        /**
         * [Exponential] with **equal jitter**, and the default.
         *
         * Un-jittered backoff synchronises a fleet: one carrier-level blip
         * drops thousands of devices at once and they all retry at t+1s, t+2s,
         * t+4s together, turning an outage into a self-inflicted stampede on
         * recovery. A browser is partly shielded by users being spread across
         * time and tabs; a mobile fleet coming back together is not.
         *
         * Equal jitter (`base/2 + random(0, base/2)`) is used rather than full
         * jitter (`random(0, base)`) because full jitter can produce a
         * near-zero delay that retries almost immediately, defeating the
         * backoff on exactly the attempt that most needed it.
         */
        public val Default: RetryDelay = equalJitter(Exponential)

        /**
         * Spread [base] over `[base/2, base]`.
         *
         * [random] is injectable so consumer tests stay deterministic.
         */
        public fun equalJitter(
            base: RetryDelay,
            random: Random = Random.Default,
        ): RetryDelay = RetryDelay { attemptIndex, error ->
            val baseMillis = base.delayFor(attemptIndex, error).inWholeMilliseconds
            val half = baseMillis / 2
            // nextLong requires until > from.
            val spread = if (half > 0) random.nextLong(half) else 0L
            (half + spread).milliseconds
        }

        /** The same delay before every attempt. */
        public fun constant(delay: Duration): RetryDelay = RetryDelay { _, _ -> delay }
    }
}

private fun exponentialBackoff(attemptIndex: Int): Duration {
    // Guard the shift: RetryPolicy.Forever can drive attemptIndex arbitrarily
    // high, and `1000L shl 64` wraps rather than saturating.
    if (attemptIndex < 0) return Duration.ZERO
    if (attemptIndex >= 31) return RetryDelay.MaxBackoff
    val millis = 1_000L shl attemptIndex
    return minOf(millis, RetryDelay.MaxBackoff.inWholeMilliseconds).milliseconds
}
