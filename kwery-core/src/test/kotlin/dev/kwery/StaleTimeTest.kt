package dev.kwery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StaleTimeTest {

    private val loadedAt = 1_000_000L

    @Test
    fun `default is stale immediately`() {
        assertTrue(StaleTime.Zero.isStale(loadedAt, loadedAt))
    }

    @Test
    fun `After is fresh until the duration elapses`() {
        val stale = StaleTime.of(30.seconds)
        assertFalse(stale.isStale(loadedAt, loadedAt))
        assertFalse(stale.isStale(loadedAt, loadedAt + 29_999))
        assertTrue(stale.isStale(loadedAt, loadedAt + 30_000), "boundary is inclusive")
        assertTrue(stale.isStale(loadedAt, loadedAt + 60_000))
    }

    @Test
    fun `data that never loaded is always stale`() {
        assertTrue(StaleTime.of(5.minutes).isStale(null, loadedAt))
        assertTrue(StaleTime.Infinite.isStale(null, loadedAt))
        assertTrue(StaleTime.Static.isStale(null, loadedAt))
    }

    @Test
    fun `Infinite and Static are never stale by time`() {
        assertFalse(StaleTime.Infinite.isStale(loadedAt, loadedAt + 10_000_000))
        assertFalse(StaleTime.Static.isStale(loadedAt, loadedAt + 10_000_000))
    }

    @Test
    fun `Infinite yields to invalidation but Static does not`() {
        // The entire reason both exist.
        assertTrue(StaleTime.Infinite.allowsInvalidation)
        assertFalse(StaleTime.Static.allowsInvalidation)
        assertTrue(StaleTime.of(5.seconds).allowsInvalidation)
    }

    @Test
    fun `Static blocks automatic refetch triggers`() {
        assertFalse(StaleTime.Static.allowsAutomaticRefetch)
        assertTrue(StaleTime.Infinite.allowsAutomaticRefetch)
        assertTrue(StaleTime.Zero.allowsAutomaticRefetch)
    }

    // ---- Wall-clock robustness -------------------------------------------

    @Test
    fun `a backwards clock jump is treated as stale, not fresh forever`() {
        // NTP correction or the user changing the device clock puts
        // dataUpdatedAt in the future. A naive `now - at >= staleTime` would
        // report the data fresh until the clock caught up, potentially for
        // hours, with no way to recover. Refetching early is the safe
        // direction.
        val stale = StaleTime.of(30.seconds)
        val clockWentBack = loadedAt - 60_000
        assertTrue(stale.isStale(loadedAt, clockWentBack))
    }

    @Test
    fun `isElapsed handles the ordinary forward case`() {
        assertFalse(isElapsed(nowMillis = 100, sinceMillis = 0, durationMillis = 200))
        assertTrue(isElapsed(nowMillis = 200, sinceMillis = 0, durationMillis = 200))
        assertTrue(isElapsed(nowMillis = 300, sinceMillis = 0, durationMillis = 200))
    }

    @Test
    fun `zero duration is always elapsed`() {
        assertTrue(isElapsed(nowMillis = 0, sinceMillis = 0, durationMillis = 0))
    }
}
