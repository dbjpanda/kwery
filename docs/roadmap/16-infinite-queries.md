# 16 — Infinite & Paginated Queries

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | **gate 2 in progress** — reconciled; one known gap (#8046 per-page retry) |
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

**Concurrent `fetchNextPage` calls are conflated structurally**, and this turned
out to need no code at all.

The first implementation added an explicit guard plus an
`allowConcurrentPageFetches` escape hatch. Mutation testing then showed that
deleting the guard changed nothing: all pages live in **one** cache entry, and
the entry already deduplicates in-flight fetches, so a second `fetchNextPage`
joins the existing request rather than starting a competing one.

The guard was removed and the option deleted. An option that silently cannot be
honoured is worse than no option — and the safety is better for being
structural, since there is no guard left to forget. TanStack's documented advice
("verify the query is not `isFetching`") is work Kwery's users simply never have
to do.

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
| Null page param vs "no more pages" | conflated | disambiguated by `P : Any` | divergent (better) |
| Refetch drops trailing pages when list shrinks | yes | yes | done |
| Per-page retry (#8046) | yes | **not yet** — retries the whole page walk | **gap** |
| `getNextPageParam` | yes | yes | planned |
| `getPreviousPageParam` (bidirectional) | yes | yes | planned |
| `hasNextPage` / `hasPreviousPage` | yes | yes | planned |
| `fetchNextPage` / `fetchPreviousPage` | yes | yes | planned |
| `isFetchingNextPage` / `…Previous` | yes | yes | planned |
| Single cache entry, single in-flight fetch | yes | yes | planned |
| `cancelRefetch` control | yes | **not needed** — conflation is structural, see below | divergent |
| Sequential refetch of all pages | yes | `RefetchStrategy.AllPages` (default) | planned |
| `maxPages` window | yes | yes | planned |
| Manual page manipulation via `setQueryData` | yes | yes, typed | planned |
| Reverse display via `select` | yes | `select` | planned |
| Paginated (non-infinite) via `KeepPrevious` | yes | yes, see [09](09-manual-cache.md) | planned |
| Cheaper refetch strategies | **no** | `FirstPageOnly`, `Windowed(n)` | divergent (better) |
| Concurrent-fetch guard by default | no, documented as the caller's job | yes, structural | divergent (better) |
| Lazy-list "near end" helper | no | `kwery-compose` | divergent (addition) |

## Open questions

- **OQ-1.** ~~Should `FirstPageOnly` be the default?~~ **Closed: no, `AllPages`
  stays the default.** It is the only correct choice for cursor APIs, where each
  page's parameter comes from the previous page's response and a stale cursor
  duplicates or skips records. Correctness wins over cost for a default; the
  cheaper strategies are documented and one line away for offset-paginated APIs.
- **OQ-2.** *(unchanged)* How do infinite queries interact with persistence
  ([15](15-persistence.md))? Persisting 40 pages of a feed can dominate the
  cache. Options: persist only the first `n` pages, or exclude infinite queries
  by default. Leaning: persist `min(pages, maxPages ?: 3)` and make it
  configurable.
- **OQ-3.** ~~Expose a flattened view?~~ **Closed: `flatten { it.items }`.**
  The library cannot know a page's shape, so it takes the accessor as a
  parameter rather than constraining pages to be lists. One line at the call
  site, no restriction on page shape.

## Reconciliation against the vendored suite

Reading `infiniteQueryBehavior.test.tsx` and `infiniteQueryObserver.test.tsx`
found two real bugs and one type-safety hole.

**Bug: a refetch must DROP trailing pages when the list shrinks.** If
`getNextPageParam` now returns null earlier than before, the server no longer
has those pages, and keeping the cached tail shows data that no longer exists.
Kwery was keeping them — the same code path that legitimately preserves pages
for the cheap strategies. Why the loop ended now decides: exhausted means drop,
strategy-limited means keep. **Verified by mutation.**

**Type-safety hole: `null` as a page parameter versus null meaning "no more
pages".** JavaScript conflates these — TanStack's `initialPageParam: null` is a
valid parameter while `getNextPageParam` returning null means "stop". A Kotlin
port using `P?` for both inherits the ambiguity. `P` is now bound to
`P : Any`, so `getNextPageParam`'s `P?` unambiguously means the end. A
genuinely null cursor is expressed with a sentinel value.

**Confirmed correct without change:** `maxPages` evicts from the end opposite
the fetch direction; page-param callbacks are never invoked on empty pages;
cancelling a refetch preserves the previously loaded pages.

### Known gap: per-page retry (#8046)

TanStack has a regression test for an infinite loop where "the retryer every
time restarts from page 1 once it reaches the page where it errors". Kwery has
the same shape of problem: an `AllPages` refetch runs inside the entry's single
fetch, so the entry's retry policy retries **the whole page walk**, not the
page that failed.

With the default `RetryPolicy.ForMutations`-style settings this is bounded, but
under `RetryPolicy.Forever` it would re-walk from page 1 indefinitely. Fixing it
means per-page retry inside the refetch loop rather than delegating to the
entry. **Not yet done**, and gate 2 stays open because of it.

## Definition of done

- [x] `InfiniteData`, `infiniteQuery`, page-fetching implemented.
- [x] Test: pages accumulate under one key; `pageParams` stay aligned.
- [x] Test: `getNextPageParam` returning null sets `hasNextPage` false, and a
      further `fetchNextPage` is a no-op rather than an error.
- [x] Test: bidirectional fetching prepends, keeping params in order.
- [x] Test: `hasPreviousPage` is false without a previous-page function.
- [x] Test: overlapping `fetchNextPage` calls cost one request.
- [x] Test: `AllPages` refetch is sequential, in order, from page 1.
- [x] Test: `FirstPageOnly` issues exactly one request and keeps the pages
      already scrolled past.
- [x] Test: `Windowed(n)` refetches the first n pages.
- [x] Test: `maxPages` evicts from the **front** when paging forward and the
      **back** when paging backward.
- [x] Test: misaligned `pages`/`pageParams` are rejected at construction.
- [ ] Test: `setQueryData` page manipulation keeps them aligned.
- [ ] OQ-2 (how many pages to persist) resolved and reflected in
      [15](15-persistence.md).
- [x] Reconciled against `infiniteQueryBehavior.test.tsx` and
      `infiniteQueryObserver.test.tsx` — see above.
- [x] Test: a refetch drops trailing pages when the list shrinks.
- [x] Test: a refetch re-derives page params rather than replaying stored ones.
- [x] Test: page-param callbacks are not invoked on empty pages.
- [x] Test: cancelling a refetch preserves the previously loaded pages.
- [ ] Per-page retry, so a failing page does not re-walk from page 1 (#8046).
