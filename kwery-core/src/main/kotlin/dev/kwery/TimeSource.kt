package dev.kwery

/**
 * Supplies the current time to the cache.
 *
 * Every time-dependent decision — staleness, garbage collection, retry
 * backoff — reads the clock through this rather than calling
 * `System.currentTimeMillis()` directly, so tests can drive them with a virtual
 * clock instead of real `delay()`. A suite that takes minutes is a suite that
 * gets skipped.
 *
 * Values are epoch milliseconds, not a monotonic counter, because
 * [QueryState.dataUpdatedAt] is user-visible and must survive being persisted
 * and reloaded in a later process. See [isElapsed] for how clock jumps are
 * handled.
 */
public fun interface TimeSource {
    public fun nowMillis(): Long

    public companion object {
        /** Wall-clock time. */
        public val System: TimeSource = TimeSource { java.lang.System.currentTimeMillis() }
    }
}

/**
 * True when at least [durationMillis] has passed since [sinceMillis].
 *
 * Wall-clock time can move **backwards** — an NTP correction, or the user
 * changing the device clock. That would make `now - sinceMillis` negative, and
 * a naive comparison would then report the data as fresh until the clock caught
 * up, potentially for hours.
 *
 * A backwards jump is therefore treated as **elapsed**. Refetching sooner than
 * necessary is a cost; serving stale data indefinitely with no way to recover
 * is a bug.
 */
internal fun isElapsed(nowMillis: Long, sinceMillis: Long, durationMillis: Long): Boolean {
    val elapsed = nowMillis - sinceMillis
    if (elapsed < 0) return true
    return elapsed >= durationMillis
}
