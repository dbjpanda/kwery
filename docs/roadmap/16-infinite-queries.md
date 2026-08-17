# 16 — Infinite & Paginated Queries

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | planned |
| **Module** | `kwery-core`, `kwery-compose` |
| **TanStack source** | [`guides/infinite-queries.md`](../../.reference/tanstack-query/docs/framework/react/guides/infinite-queries.md), [`guides/paginated-queries.md`](../../.reference/tanstack-query/docs/framework/react/guides/paginated-queries.md) |
| **Depends on** | 03 Query state, 09 Manual cache |

Two different problems that are easy to conflate:

- **Paginated** — one page at a time, page N replaces page N−1. Solved entirely
  by an ordinary query whose key includes the page, plus
  `PlaceholderData.KeepPrevious` from [09](09-manual-cache.md) so the list does
  not flash empty. **No new machinery needed.**
- **Infinite** — pages accumulate into one growing list under a single key.
  This is what needs a subsystem.

## TanStack behaviour

`useInfiniteQuery` differs from `useQuery` in that `data` becomes
`{pages: TPage[], pageParams: TParam[]}`.

- `initialPageParam` is **required**.
- `getNextPageParam(lastPage, allPages, lastPageParam, allPageParams)` returns
  the next param, or `null`/`undefined` to signal the end. `hasNextPage` derives
  from it. `getPreviousPageParam` mirrors it for bidirectional lists.
- `fetchNextPage` / `fetchPreviousPage`; `isFetchingNextPage` /
  `isFetchingPreviousPage` distinguish "loading more" from "background refresh".
- **One cache entry, one in-flight fetch.** Calling `fetchNextPage` during an
  in-flight fetch risks overwriting data; `cancelRefetch: false` (default `true`)
  permits simultaneous fetching. The docs recommend guarding on `isFetching`.
- **Refetching an infinite query refetches every page sequentially**, from the
  first, so stale cursors do not produce duplicates or gaps. A 40-page list means
  40 sequential requests.
- `maxPages` caps retained pages, bounding both memory and that refetch cost —
  it requires both `getNextPageParam` and `getPreviousPageParam` so the window
  can move in either direction.

## Kwery design

```kotlin
data class InfiniteData<P, T>(
    val pages: List<T>,
    val pageParams: List<P>,
)

data class InfiniteQueryOptions<P, T>(
    val initialPageParam: P,
    val getNextPageParam: (lastPage: T, allPages: List<T>, lastPageParam: P) -> P?,
    val getPreviousPageParam: ((firstPage: T, allPages: List<T>, firstPageParam: P) -> P?)? = null,
    val maxPages: Int? = null,
)
```

```kotlin
val feed = client.infiniteQuery(
    key = FeedKey(filter),
    initialPageParam = 0,
    getNextPageParam = { last, _, _ -> last.nextCursor },
) { pageParam -> api.feed(cursor = pageParam) }

feed.state          // Flow<QueryState<InfiniteData<Int, FeedPage>>>
feed.fetchNextPage()
feed.hasNextPage    // StateFlow<Boolean>
```

`P?` returning null to mean "no more pages" maps cleanly onto Kotlin nullability,
replacing TanStack's `null | undefined` ambiguity.

### Where Kwery should do better

**The sequential-refetch cost is a genuine problem, not a quirk.** A user who has
scrolled 40 pages and backgrounds the app triggers, on foreground, 40 sequential
requests — slow, expensive on cellular, and a server-load spike across a fleet.
TanStack's only mitigation is `maxPages`.

Kwery adds `RefetchStrategy`:

- `AllPages` — TanStack parity. Correct for cursor APIs where earlier pages
  affect later cursors.
- `FirstPageOnly` — refetch only page 1 and merge; the rest stay cached. Correct
  for offset/ID-based APIs where pages are independent. Cheap and usually right.
- `Windowed(n)` — refetch the first `n` pages.

Default is `AllPages` for parity and correctness; the docs should make clear that
`FirstPageOnly` is the right choice for most offset-paginated REST APIs.

**Concurrent `fetchNextPage` calls** are guarded by default rather than left to
the caller: overlapping calls are conflated (the second is ignored while the
first is in flight) unless `allowConcurrentPageFetches = true`. TanStack's
documented advice — "verify the query is not `isFetching`" — is exactly the kind
of guard a library should provide instead of request.

### Compose integration

`kwery-compose` provides a `LazyListState`-driven helper so the common
"fetch when near the end" wiring is not rewritten per screen:

```kotlin
LazyColumn(state = listState) { /* … */ }
feed.fetchNextPageWhenNearEnd(listState, threshold = 5)
```

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `pages` + `pageParams` structure | yes | `InfiniteData` | planned |
| Required `initialPageParam` | yes | yes | planned |
| `getNextPageParam` | yes | yes | planned |
| `getPreviousPageParam` (bidirectional) | yes | yes | planned |
| `hasNextPage` / `hasPreviousPage` | yes | yes | planned |
| `fetchNextPage` / `fetchPreviousPage` | yes | yes | planned |
| `isFetchingNextPage` / `…Previous` | yes | yes | planned |
| Single cache entry, single in-flight fetch | yes | yes | planned |
| `cancelRefetch` control | yes | `allowConcurrentPageFetches` | planned |
| Sequential refetch of all pages | yes | `RefetchStrategy.AllPages` (default) | planned |
| `maxPages` window | yes | yes | planned |
| Manual page manipulation via `setQueryData` | yes | yes, typed | planned |
| Reverse display via `select` | yes | `select` | planned |
| Paginated (non-infinite) via `KeepPrevious` | yes | yes, see [09](09-manual-cache.md) | planned |
| Cheaper refetch strategies | **no** | `FirstPageOnly`, `Windowed(n)` | divergent (better) |
| Concurrent-fetch guard by default | no, documented | yes | divergent (better) |
| Lazy-list "near end" helper | no | `kwery-compose` | divergent (addition) |

## Open questions

- **OQ-1.** Should `FirstPageOnly` be the default? It is right for most REST
  APIs and dramatically cheaper, but it is silently wrong for cursor APIs where
  an insertion shifts every subsequent cursor. Correctness should win:
  keep `AllPages`, document loudly.
- **OQ-2.** How do infinite queries interact with persistence
  ([15](15-persistence.md))? Persisting 40 pages of a feed can dominate the
  cache. Options: persist only the first `n` pages, or exclude infinite queries
  by default. Leaning: persist `min(pages, maxPages ?: 3)` and make it
  configurable.
- **OQ-3.** Should `InfiniteData` expose a flattened `items` view? Every
  consumer writes `pages.flatMap { it.items }`, but the flattening is
  page-shape-specific so the library cannot do it generically — unless pages
  are required to be `List<T>`, which would be too restrictive. Possibly a
  `select` recipe in the docs instead.

## Definition of done

- [ ] `InfiniteData`, `infiniteQuery`, page-fetching implemented.
- [ ] Test: pages accumulate under one key; `pageParams` stay aligned.
- [ ] Test: `getNextPageParam` returning null sets `hasNextPage` false.
- [ ] Test: bidirectional fetching prepends and appends correctly.
- [ ] Test: concurrent `fetchNextPage` calls are conflated by default.
- [ ] Test: `AllPages` refetch is sequential, in order, from page 1.
- [ ] Test: `FirstPageOnly` refetches exactly one request and merges.
- [ ] Test: `maxPages` evicts from the correct end when fetching each direction.
- [ ] Test: `setQueryData` page manipulation keeps `pages`/`pageParams` aligned.
- [ ] OQ-2 resolved and reflected in [15](15-persistence.md).
