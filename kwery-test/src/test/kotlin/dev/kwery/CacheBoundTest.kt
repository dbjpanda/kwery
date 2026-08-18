package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private data class RowKey(val id: Int) : QueryKey<String> {
    override val parts get() = listOf("row", id)
}

/**
 * `maxEntries`: the cache is bounded by count, not only by time.
 *
 * `gcTime` bounds the cache by time and nothing else bounds it by size. A
 * browser tab gets reloaded; an Android process can live for days, so a user
 * scrolling a long list of detail pages would otherwise accumulate every
 * response they ever touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheBoundTest {

    @Test
    fun `the cache does not grow past maxEntries`() = runTest {
        val kwery = TestQueryClient(this, maxEntries = 10)

        // Fifty keys, each observed briefly then released — scrolling a list.
        repeat(50) { i ->
            val job = backgroundScope.launch { kwery.query(RowKey(i)) { "v$i" }.collect { } }
            kwery.settle()
            job.cancel()
            kwery.settle(6.seconds)
        }

        assertTrue(
            kwery.client.cacheSnapshot().size <= 10,
            "bounded by count, was ${kwery.client.cacheSnapshot().size}",
        )
    }

    @Test
    fun `eviction takes the least recently used first`() = runTest {
        val kwery = TestQueryClient(this, maxEntries = 3)

        repeat(3) { i ->
            val job = backgroundScope.launch { kwery.query(RowKey(i)) { "v$i" }.collect { } }
            kwery.settle()
            job.cancel()
            kwery.settle(6.seconds)
        }

        // Touch row 0 so it is no longer the oldest.
        val touch = backgroundScope.launch { kwery.query(RowKey(0)) { "again" }.collect { } }
        kwery.settle()
        touch.cancel()
        kwery.settle(6.seconds)

        // A fourth key pushes one out. It should be row 1, the oldest untouched.
        val job = backgroundScope.launch { kwery.query(RowKey(3)) { "v3" }.collect { } }
        kwery.settle()
        job.cancel()
        kwery.settle(6.seconds)

        val keys = kwery.client.cacheSnapshot().map { it.key }
        assertNull(keys.firstOrNull { it == RowKey(1) }, "the least recently used goes, saw $keys")
        assertNotNull(keys.firstOrNull { it == RowKey(0) }, "the recently touched one stays")
    }

    @Test
    fun `an observed entry is never evicted, whatever the pressure`() = runTest {
        val kwery = TestQueryClient(this, maxEntries = 3)
        val pinned = RowKey(999)

        val holder = backgroundScope.launch { kwery.query(pinned) { "on screen" }.collect { } }
        kwery.settle()

        // Far more keys than the bound allows, all released immediately.
        val jobs = mutableListOf<Job>()
        repeat(30) { i ->
            jobs += backgroundScope.launch { kwery.query(RowKey(i)) { "v$i" }.collect { } }
            kwery.settle()
        }
        jobs.forEach { it.cancel() }
        kwery.settle(6.seconds)

        assertNotNull(
            kwery.client.cacheSnapshot().firstOrNull { it.key == pinned },
            "discarding data that is on screen is worse than using the memory",
        )
        assertEquals("on screen", kwery.client.getQueryData(pinned))

        holder.cancel()
    }
}
