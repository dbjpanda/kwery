package dev.kwery

/**
 * One cache entry, lifted out of the cache for storage.
 *
 * Deliberately carries `Any?` data and no serialization concept: `kwery-core`
 * knows nothing about how bytes are produced (AD-1's sibling — core stays
 * dependency-light). `kwery-persist` turns these into a storable form.
 */
public data class DehydratedEntry(
    val key: QueryKey<*>,
    val data: Any?,
    val dataUpdatedAt: Long,
)

/**
 * Lift every entry that holds data out of the cache.
 *
 * Entries with no data, and entries whose data is currently **optimistic**, are
 * skipped: persisting an unconfirmed write would resurrect it on next launch as
 * though the server had accepted it.
 */
public suspend fun QueryClient.dehydrate(): List<DehydratedEntry> = dehydrateInternal()

/**
 * Write entries back into the cache.
 *
 * Hydrated entries keep their original `dataUpdatedAt`, so staleness is judged
 * against when the data was actually fetched — not when it was restored. A
 * cache restored after two minutes with a five-minute `staleTime` is still
 * fresh and does not refetch, which is the entire point of persisting it.
 */
public suspend fun QueryClient.hydrate(entries: List<DehydratedEntry>): Unit =
    hydrateInternal(entries)
