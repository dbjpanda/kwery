# 09 — Manual Cache Access & Seed Data

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | **gate 2 complete** |
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
// Enters the cache, and honours when the data was actually obtained.
client.query(
    key = TodoKey(id),
    initialData = InitialData({ listCache.find { it.id == id } }, updatedAt = listLoadedAt),
) { api.todo(id) }

// Never cached; belongs to this observer's stream.
pageFlow.flatMapLatest { client.query(PageKey(it)) { … } }.keepPreviousData()
```

The two end up in genuinely different places — one a query parameter, one a
`Flow` operator — which makes them impossible to confuse. TanStack's two
similarly-named options are the thing that invites the mistake.

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
| `setQueriesData` (bulk, by filter) | yes | yes | done |
| `initialData` | yes | `InitialData` | done |
| `initialData` as a function | yes | yes, lazy — not called if the entry exists | done |
| `initialDataUpdatedAt` | yes | `InitialData.updatedAt` | done |
| `placeholderData` | yes | `keepPreviousData()` operator | divergent (see below) |
| `keepPreviousData` | yes | `Flow.keepPreviousData()` | done |
| `isPlaceholderData` flag | yes | on `QueryState` | planned |
| Seeded entry can later refetch | no — frozen forever | adopts a fetcher on first observe | divergent (better) |
| Non-nullable updater | no | `updateQueryData` | divergent (addition) |

## Deliberate divergences

1. **Typed reads and writes.** The single clearest ergonomic win of AD-3.
2. **`initialData` and `placeholderData` are different things**, not two options
   of the same shape.

3. **`keepPreviousData` is a `Flow` operator, not a query option.** "Previous"
   is a property of an *observer's history*, not of the cache entry: two screens
   paging independently have different previous values, and the cache has no
   opinion about either. Modelling it as an option would push per-observer state
   into shared state. As an operator it composes with `flatMapLatest` over a
   changing key, which is how paginated lists are written anyway, and nothing is
   ever written to the cache.

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

- [x] All read/write methods implemented and typed.
- [x] Test: `setQueryData` seeds an entry that later adopts a fetcher.
- [x] Test: `initialData` enters the cache and appears in the dehydrated state;
      `keepPreviousData` never touches the cache.
- [x] Test: fresh `initialData` suppresses the initial fetch entirely.
- [x] Test: seed data never overwrites an existing entry, and its producer is
      not even called then. **Verified by mutation.**
- [x] Test: a null seed value seeds nothing and the query fetches normally.
- [x] Test: `keepPreviousData()` shows the old key's data with
      `isPlaceholderData` true while the new key loads.
- [x] Test: an error is surfaced rather than hidden behind a stale page.
- [x] Test: the placeholder is never written to the cache.
- [x] Test: paging back to a cached page is real data, not a placeholder.
- [x] Test: `InitialData.updatedAt` in the past renders immediately **and**
      refetches, because the seed was already stale. **Verified by mutation** —
      ignoring the caller's timestamp makes the query skip the refetch.
