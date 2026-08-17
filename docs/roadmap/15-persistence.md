# 15 — Persistence & Hydration

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | **gate 2 complete** for the contracts and hydration; DataStore/Room persisters pending |
| **Module** | `kwery-persist`, `kwery-persist-datastore`, `kwery-persist-room` |
| **TanStack source** | [`plugins/persistQueryClient.md`](../../.reference/tanstack-query/docs/framework/react/plugins/persistQueryClient.md), [`plugins/createPersister.md`](../../.reference/tanstack-query/docs/framework/react/plugins/createPersister.md), [`reference/hydration.md`](../../.reference/tanstack-query/docs/framework/react/reference/hydration.md) |
| **Depends on** | 01 Query keys, 04 Caching lifecycle |

The headline feature and the clearest gap in the Kotlin ecosystem. Process death
is normal on Android; a cache that does not survive it is a cache that is cold
exactly when users notice.

## TanStack behaviour

A tiny persister interface, whole-cache granularity:

```ts
interface Persister {
  persistClient(client: PersistedClient): Promisable<void>
  restoreClient(): Promisable<PersistedClient | undefined>
  removeClient(): Promisable<void>
}

interface PersistedClient {
  timestamp: number
  buster: string
  clientState: DehydratedState
}
```

- `persistQueryClient` restores once, then subscribes to cache changes.
- Writes are **throttled to at most once per second**.
- The stored cache is discarded wholesale if it is expired (`maxAge`, default 24
  hours), busted (`buster` mismatch), errored, or empty.
- **`gcTime` must be ≥ `maxAge`.** Otherwise garbage collection deletes entries
  before the persisted cache would have expired. The docs warn about this and
  the library does not enforce it.
- Restoration is asynchronous, so queries must not fetch while restoring —
  `PersistQueryClientProvider` holds them in `fetchStatus: 'idle'` and
  `useIsRestoring` exposes the state.

## Kwery design

```kotlin
interface QueryPersister {
    suspend fun persist(client: PersistedClient)
    suspend fun restore(): PersistedClient?
    suspend fun remove()
}

@Serializable
data class PersistedClient(
    val timestamp: Long,
    val buster: String,
    val schemaVersion: Int,
    val entries: List<PersistedEntry>,
)
```

```kotlin
val client = QueryClient {
    persistence {
        persister = DataStorePersister(context, name = "kwery-cache")
        maxAge = 24.hours
        buster = BuildConfig.VERSION_NAME
        throttle = 1.seconds
    }
}
```

### The `gcTime` guard

Per the roadmap's "improvements" list, this is enforced rather than documented:

```
IllegalArgumentException:
  gcTime (5m) is shorter than persistence maxAge (24h).
  Entries would be evicted from memory long before the persisted cache expires,
  so the persisted cache would rarely be used.
  Set gcTime to at least 24h, or lower maxAge.
```

A silent misconfiguration that makes the headline feature quietly not work is
exactly the kind of thing a library should refuse to accept.

### Restoration ordering

Restoration is asynchronous and racy against queries starting to fetch. Kwery
exposes it explicitly:

```kotlin
val isRestoring: StateFlow<Boolean>
suspend fun awaitRestored()
```

Queries created during restoration stay in `fetchStatus: Idle` until it
completes, then evaluate staleness against the **restored** data — so a cache
restored 30 seconds ago with a 5-minute `staleTime` does not refetch.

Ordering across features is strict and must be tested as such:

```
restore cache -> apply staleness -> resume paused mutations (feature 14)
```

### Two persister implementations

- **`DataStorePersister`** — Proto/Preferences DataStore, single blob. Simple,
  transactional, good to a few hundred KB. The default.
- **`RoomPersister`** — per-entry rows in SQLite. Supports partial writes,
  per-query eviction, and caches in the multi-MB range without rewriting the
  whole blob on every change.

The whole-blob approach degrades badly on Android: a 2 MB cache rewritten every
second drains battery and blocks I/O. Offering a row-based persister is a
concrete improvement over TanStack's design, whose `createPersister` per-query
variant is a late addition rather than the default shape.

### Serialization

Entries are serialised with kotlinx-serialization. `PersistedEntry` stores the
canonical key string (from [01](01-query-keys.md)), the encoded data, timestamps,
and the data's declared type identity. On restore, a type mismatch — because the
app updated and a response shape changed — **discards that entry** rather than
throwing. `schemaVersion` guards Kwery's own format changes independently of the
user's `buster`.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Persister interface | 3 methods | 3 methods | planned |
| Whole-client dehydrate/hydrate | yes | yes | planned |
| `maxAge` expiry, default 24 h | yes | yes | planned |
| `buster` cache busting | yes | yes | planned |
| Discard on expired/busted/corrupt | yes | yes | planned |
| Throttled writes (1 s default) | yes | yes | planned |
| Restore-in-progress state | `useIsRestoring` | `isRestoring` / `awaitRestored()` | planned |
| Queries idle during restore | yes | yes | planned |
| `dehydrateOptions` / `hydrateOptions` filters | yes | `persistFilter` predicate | planned |
| Per-query persistence opt-out | via filters | `persist = false` per query | planned |
| Persist paused mutations | yes | see [14](14-offline-mutation-queue.md) | planned |
| `gcTime` ≥ `maxAge` enforced | **no**, documented only | **yes, throws** | done |
| Persistence opt-in per key | via dehydrate filters | `PersistableQueryKey` carries its serializer | divergent (better) |
| Unconfirmed optimistic writes excluded | no | yes | divergent (better) |
| Discard reason surfaced | no | `PersistedCache.discardReason` | divergent (addition) |
| Row-based persister | late `createPersister` | `RoomPersister`, first-class | divergent (better) |
| Kwery-format `schemaVersion` | no | yes | divergent (better) |
| Type-mismatch entry discarded, not thrown | n/a | yes | divergent (better) |

## Open questions

- **OQ-1.** Blocking, shared with [14](14-offline-mutation-queue.md) OQ-1: cache
  and mutation queue in one store or two? Strong lean toward two, so a `buster`
  bump never discards pending user writes.
- **OQ-2.** Should persistence be encrypted by default? Cached API responses
  routinely contain personal data, and this is a library that will be used
  without much thought about it. `EncryptedFile`/Tink adds a dependency and
  meaningful write cost. Leaning: not by default, but ship
  `EncryptedDataStorePersister` and address it prominently in the docs.
- **OQ-3.** Should restore be lazy per key rather than eager whole-cache? Lazy
  makes cold start faster with a large cache, but complicates staleness
  evaluation and the `isRestoring` contract. Consider for `RoomPersister` only.
- **OQ-4.** What happens when the persisted payload exceeds a size threshold?
  Silently growing to tens of MB is a real risk. Leaning: a configurable soft
  cap that evicts least-recently-used entries at persist time, plus a warning.

## Definition of done

- [x] `QueryPersister`, `PersistedClient`, dehydrate/hydrate implemented.
- [x] Test: cache survives being written and restored into a **new client**,
      which is what a cold start is.
- [x] Test: `buster` change discards the cache **and deletes the stored copy**.
- [x] Test: snapshot older than `maxAge` discarded.
- [x] Test: an unreadable store is discarded rather than crashing.
- [x] Test: schema-version mismatch discarded, independently of `buster`.
- [x] Test: an entry whose shape changed between app versions is dropped
      **without discarding the rest of the snapshot**.
- [x] Test: queries created during restore stay `Idle` rather than racing it.
- [x] Test: restored data keeps its original `dataUpdatedAt`, so a cache
      restored inside `staleTime` does **not** refetch — the point of the feature.
- [x] Test: construction throws when `gcTime < maxAge`. **Verified by mutation.**
- [x] Test: a key with no serializer is never written.
- [x] Test: an **unconfirmed optimistic write is never persisted**. **Verified
      by mutation**: without the guard, storage contains the optimistic value.
- [ ] `DataStorePersister` and `RoomPersister` implemented (Android modules).
- [ ] Test: writes throttled to at most once per `throttle` window.
- [ ] Benchmark: cold-start restore time for 100 / 1 000 / 10 000 entries.
