package dev.kwery.persist

import dev.kwery.DehydratedEntry
import dev.kwery.QueryClient
import dev.kwery.TimeSource
import dev.kwery.dehydrate
import dev.kwery.encodeKey
import dev.kwery.hydrate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** How a [QueryClient]'s cache is persisted. */
public class PersistOptions(
    public val persister: QueryPersister,

    /**
     * Every key that may be restored.
     *
     * A stored entry arrives as bytes with no type, and only its key carries a
     * serializer — so a restore can decode an entry only if its key is declared
     * here. This is the same shape of constraint that makes resuming persisted
     * *writes* require registered mutation functions: serialized state cannot
     * carry code.
     *
     * Declared per-client rather than in a global registry, so two clients (and
     * two tests) never interfere.
     */
    public val keys: List<PersistableQueryKey<*>> = emptyList(),

    /**
     * How old a stored snapshot may be before it is discarded wholesale.
     *
     * Must be ≤ the client's `gcTime`, or entries would be evicted from memory
     * long before the persisted copy expires — see [PersistedCache].
     */
    public val maxAge: Duration = 24.hours,

    /** Application cache version. Changing it discards the stored snapshot. */
    public val buster: String = "",

    /** Minimum interval between writes. Cache changes in between are coalesced. */
    public val throttle: Duration = 1.seconds,

    /** Keys whose data should never be written, even if persistable. */
    public val exclude: (dev.kwery.QueryKey<*>) -> Boolean = { false },
)

/** Why a stored snapshot was thrown away. Surfaced for diagnosis. */
public enum class DiscardReason { None, Expired, BusterMismatch, SchemaMismatch, Unreadable }

/** A running persistence subscription. */
public class PersistedCache internal constructor(
    private val job: Job,
    public val discardReason: DiscardReason,
    public val restoredEntryCount: Int,
) {
    /** Stop persisting. The stored snapshot is left as-is. */
    public fun close() {
        job.cancel()
    }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Restore a persisted cache, then keep it up to date.
 *
 * ```kotlin
 * val persisted = client.persist(
 *     scope = applicationScope,
 *     options = PersistOptions(persister = DataStorePersister(context), buster = BuildConfig.VERSION_NAME),
 * )
 * ```
 *
 * Restoration happens first and holds `client.isRestoring` true throughout, so
 * queries created during it wait rather than racing it with a network request.
 *
 * A stored snapshot is discarded wholesale — never partially — when it is
 * expired, busted, written by a different schema version, or unreadable. Partial
 * recovery of a corrupt cache is not worth the risk of serving a half-restored
 * one.
 *
 * @throws IllegalArgumentException if `gcTime` is shorter than
 *   [PersistOptions.maxAge]. TanStack documents this constraint and lets you
 *   violate it silently, which quietly defeats the whole feature: entries are
 *   evicted from memory long before the stored copy expires, so the persisted
 *   cache is written but almost never used.
 */
public suspend fun QueryClient.persist(
    scope: CoroutineScope,
    options: PersistOptions,
): PersistedCache {
    val gcTime = config.defaultQueryOptions.gcTime
    require(gcTime >= options.maxAge) {
        "gcTime ($gcTime) is shorter than persistence maxAge (${options.maxAge}). " +
            "Entries would be evicted from memory long before the persisted cache " +
            "expires, so the persisted cache would rarely be used. " +
            "Raise gcTime to at least ${options.maxAge}, or lower maxAge."
    }

    val timeSource = config.timeSource
    var reason = DiscardReason.None
    var restoredCount = 0

    withRestoring {
        val stored = try {
            options.persister.restore()
        } catch (unreadable: Throwable) {
            reason = DiscardReason.Unreadable
            null
        }

        when {
            stored == null -> Unit
            stored.schemaVersion != PersistedClient.CURRENT_SCHEMA_VERSION ->
                reason = DiscardReason.SchemaMismatch
            stored.buster != options.buster -> reason = DiscardReason.BusterMismatch
            timeSource.nowMillis() - stored.timestamp > options.maxAge.inWholeMilliseconds ->
                reason = DiscardReason.Expired
            else -> restoredCount = hydrateFrom(stored, options)
        }

        if (reason != DiscardReason.None) options.persister.remove()
    }

    val job = scope.launch { persistLoop(options, timeSource) }
    return PersistedCache(job, reason, restoredCount)
}

/** Hydrate what can be decoded. A single bad entry is dropped, not fatal. */
private suspend fun QueryClient.hydrateFrom(
    stored: PersistedClient,
    options: PersistOptions,
): Int {
    @Suppress("UNCHECKED_CAST")
    val byHash = options.keys.associateBy(
        keySelector = { encodeKey(it.parts) },
        valueTransform = { it as PersistableQueryKey<Any?> },
    )
    val decoded = stored.entries.mapNotNull { entry ->
        val key = byHash[entry.keyHash] ?: return@mapNotNull null
        try {
            DehydratedEntry(key, json.decodeFromString(key.serializer, entry.data), entry.dataUpdatedAt)
        } catch (mismatch: Throwable) {
            // A response shape changed between app versions. Drop this entry
            // rather than the whole cache, and rather than crashing.
            null
        }
    }
    hydrate(decoded)
    return decoded.size
}

private suspend fun QueryClient.persistLoop(options: PersistOptions, timeSource: TimeSource) {
    var lastWritten: List<DehydratedEntry>? = null
    while (true) {
        delay(options.throttle.inWholeMilliseconds)

        val current = dehydrate()
            .filter { it.key is PersistableQueryKey<*> && !options.exclude(it.key) }

        // Nothing changed since the last write, so there is nothing to write.
        // Without this the loop writes the whole cache to disk every throttle
        // window — by default once a second, for the entire life of the
        // process, whether or not anything happened. That is invisible in a
        // test that only checks correctness and expensive on a real phone.
        //
        // The comparison is over the *dehydrated* entries rather than their
        // serialized form, so it costs no JSON encoding when idle, and over
        // values rather than timestamps, so two writes in the same millisecond
        // cannot be mistaken for none.
        if (current == lastWritten) continue
        lastWritten = current

        val entries = current
            .mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val key = entry.key as PersistableQueryKey<Any?>
                try {
                    PersistedEntry(
                        keyHash = encodeKey(key.parts),
                        data = json.encodeToString(key.serializer, entry.data),
                        dataUpdatedAt = entry.dataUpdatedAt,
                    )
                } catch (unserializable: Throwable) {
                    null
                }
            }

        options.persister.persist(
            PersistedClient(
                timestamp = timeSource.nowMillis(),
                buster = options.buster,
                entries = entries,
            ),
        )
    }
}
