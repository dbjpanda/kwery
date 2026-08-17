package dev.kwery

/**
 * Data a query starts with, before it has ever fetched.
 *
 * Use it when you genuinely already have the value — the detail you are about
 * to show was in the list you just loaded — so the screen renders immediately
 * instead of spinning for something you are holding.
 *
 * ```kotlin
 * client.query(
 *     key = TodoKey(id),
 *     initialData = InitialData({ cachedList.find { it.id == id } }, updatedAt = listLoadedAt),
 * ) { api.todo(id) }
 * ```
 *
 * This is **not** placeholder data, and the difference matters:
 *
 * | | `InitialData` | [keepPreviousData] |
 * |---|---|---|
 * | Enters the cache | yes | no |
 * | Persisted | yes | no |
 * | Can suppress the first fetch | yes, via `staleTime` | no |
 * | Means | "this is real data I already have" | "show something while loading" |
 *
 * Applied only to an entry that does not exist yet. It never overwrites data
 * already in the cache, which would replace a real response with a guess.
 */
public class InitialData<T>(
    /**
     * Produces the seed value. Called at most once, and only when the entry is
     * new — so an expensive lookup costs nothing on the common path.
     *
     * Returning null seeds nothing.
     */
    public val value: () -> T?,

    /**
     * When the seed value was actually obtained, in epoch millis.
     *
     * **Set this whenever you can.** Defaulting to "now" tells the cache the
     * data is fresh, so a query with a five-minute `staleTime` will not refetch
     * for five minutes — even though the value came from a list loaded an hour
     * ago. Passing the real timestamp makes staleness honest, and stale seed
     * data refetches immediately while still rendering at once.
     */
    public val updatedAt: Long? = null,
)
