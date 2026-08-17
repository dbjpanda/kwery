# 09 — Manual Cache Access & Seed Data

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/initial-query-data.md`](../../.reference/tanstack-query/docs/framework/react/guides/initial-query-data.md), [`guides/placeholder-query-data.md`](../../.reference/tanstack-query/docs/framework/react/guides/placeholder-query-data.md), [`guides/updates-from-mutation-responses.md`](../../.reference/tanstack-query/docs/framework/react/guides/updates-from-mutation-responses.md) |
| **Depends on** | 01 Query keys |

Reading and writing the cache directly, and seeding a query before it has ever
fetched. This is where typed keys pay off most visibly.

## TanStack behaviour

`getQueryData(key)` / `setQueryData(key, updater)` read and write. `setQueryData`
takes a value or an updater function and marks the entry updated, notifying
observers — it does **not** trigger a refetch.

`initialData` and `placeholderData` look similar and are not:

| | `initialData` | `placeholderData` |
|---|---|---|
| Written to the cache | **yes** | **no** |
| Persisted | yes | no |
| Query status while showing it | `success` | `success`, plus `isPlaceholderData` |
| Respects `staleTime` | yes — can suppress the first fetch | n/a, always fetches |
| Use for | genuinely known data (e.g. from a list you already fetched) | a skeleton or a previous page while loading |

`placeholderData: keepPreviousData` keeps the previous key's data visible while
a new key loads — the reason paginated lists do not flash empty on page change.

## Kwery design

```kotlin
fun <T> QueryClient.getQueryData(key: QueryKey<T>): T?
fun <T> QueryClient.setQueryData(key: QueryKey<T>, data: T)
fun <T> QueryClient.setQueryData(key: QueryKey<T>, updater: (T?) -> T?)
fun <T> QueryClient.getQueryState(key: QueryKey<T>): QueryState<T>?
```

`QueryKey<T>` makes every one of these type-safe with no casts. In TanStack,
`getQueryData(['todos'])` returns `unknown` and users annotate by hand,
which silently rots when the endpoint's shape changes. Here it cannot compile.

```kotlin
client.query(
    key = TodoKey(id),
    initialData = { listCache.find { it.id == id } },      // enters the cache
    placeholderData = PlaceholderData.KeepPrevious,        // never cached
)
```

Modelling the two as separate parameters with distinct types — rather than
TanStack's two similarly-named options — makes the distinction hard to get
wrong, since `PlaceholderData.KeepPrevious` has no `initialData` equivalent.

`initialDataUpdatedAt` is supported, because seeding from data fetched five
minutes ago should not be treated as fresh.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `getQueryData` | yes, `unknown` | yes, typed | divergent (better) |
| `setQueryData` value | yes | yes | planned |
| `setQueryData` updater fn | yes | yes | planned |
| `setQueryData` does not refetch | yes | yes | planned |
| `getQueryState` | yes | yes | planned |
| `setQueriesData` (bulk, by filter) | yes | yes | planned |
| `initialData` | yes | yes | planned |
| `initialData` as a function | yes | yes | planned |
| `initialDataUpdatedAt` | yes | yes | planned |
| `placeholderData` | yes | `PlaceholderData` | planned |
| `keepPreviousData` | yes | `PlaceholderData.KeepPrevious` | planned |
| `isPlaceholderData` flag | yes | on `QueryState` | planned |
| Seeded entry can later refetch | no — frozen forever | adopts a fetcher on first observe | divergent (better) |
| Non-nullable updater | no | `updateQueryData` | divergent (addition) |

## Deliberate divergences

1. **Typed reads and writes.** The single clearest ergonomic win of AD-3.
2. **`initialData` and `placeholderData` are different types**, not two options
   of the same shape.

## Open questions

- **OQ-1.** ~~Should `setQueryData` create an entry that does not exist?~~
  **Closed: yes, it creates one — and unlike TanStack the orphan problem is
  fixed rather than documented.**

  Seeding a detail view from a list you already fetched is the main reason this
  method exists, so refusing to create is not an option. TanStack's resulting
  entry has no query function and can therefore never refetch or revalidate — it
  is a permanently frozen value that merely looks like a cached query.

  Kwery marks such an entry **seeded**, and when an observer later attaches
  *with* a query function, the entry **adopts** it and behaves normally from
  then on. Since the realistic sequence is "seed from the list, then navigate to
  the detail screen which observes the same key with a real fetcher", adoption
  turns the frozen value into a properly revalidating entry at exactly the
  moment it matters.

- **OQ-2.** ~~Add a non-nullable `updateQueryData`?~~ **Closed: yes, ship it.**

  ```kotlin
  fun <T> QueryClient.updateQueryData(key: QueryKey<T>, update: (T) -> T)
  ```

  No-ops when the entry is absent. The nullable receiver in `setQueryData`'s
  updater is noise in the most common optimistic-update path, where the caller
  has already established the data exists, and `it!!` inside an updater lambda
  is exactly the kind of thing that becomes a crash after a refactor.

## Definition of done

- [ ] All read/write methods implemented and typed.
- [ ] Test: `setQueryData` notifies observers without triggering a fetch.
- [ ] Test: `initialData` enters the cache and is persisted; `placeholderData`
      does neither — asserted by inspecting the dehydrated state.
- [ ] Test: with `staleTime` set, `initialData` suppresses the initial fetch;
      `placeholderData` never does.
- [ ] Test: `KeepPrevious` shows the old key's data with `isPlaceholderData`
      true while the new key loads.
- [ ] Test: `initialDataUpdatedAt` in the past makes the entry immediately stale.
