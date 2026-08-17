# Infinite queries

## The problem

Two things get called "pagination" and they need different machinery.

**Paged** — one page at a time, page 2 replaces page 1. This needs nothing new:
an ordinary query whose key contains the page number.

**Infinite** — pages accumulate into one growing list, as in a feed. That needs
a subsystem, because all the pages share one cache entry and refreshing that
entry means deciding what to do about pages the user already scrolled past.

This page is about the second.

## The simplest thing that works

```kotlin
object FeedKey : QueryKey<InfiniteData<Int, FeedPage>> {
    override val parts get() = listOf("feed")
}

val feed = client.infiniteQuery(
    key = FeedKey,
    options = InfiniteQueryOptions(
        initialPageParam = 0,
        getNextPageParam = { lastPage, _, _ -> lastPage.nextCursor },
    ),
) { cursor -> api.feed(cursor) }
```

In Compose:

```kotlin
val state by feed.stateAsState()
val listState = rememberLazyListState()

LazyColumn(state = listState) {
    items(state.data?.flatten { it.items } ?: emptyList()) { TodoRow(it) }
}

feed.FetchNextPageWhenNearEnd(listState)
```

`FetchNextPageWhenNearEnd` is the wiring every infinite list needs. Calling it
repeatedly while a page is loading costs nothing — see below.

## The end of the list

`getNextPageParam` returning **null** means there are no more pages, and
`hasNextPage()` reflects that. Calling `fetchNextPage()` afterwards is a no-op
rather than an error.

Because null is the end-of-list signal, a page parameter cannot itself be null —
`P` is bound to a non-null type. If your cursor is genuinely nullable, use a
sentinel value. JavaScript conflates these two meanings; Kotlin does not have to.

## Refetching, and what it costs

When the pages go stale, all of them are refetched **sequentially from the
first**, re-deriving each cursor from the page just received. That is the only
correct behaviour for cursor APIs: a stale cursor can duplicate or skip records.

It is also expensive. A user who scrolled forty pages triggers forty sequential
requests. If your API is offset- or id-based, pages are independent and you do
not need the strict version:

| | |
|---|---|
| `RefetchStrategy.AllPages` | Default. Correct for cursor APIs. |
| `RefetchStrategy.FirstPageOnly` | One request; later pages kept as cached. |
| `RefetchStrategy.Windowed(n)` | Refetch the first `n`. |

```kotlin
InfiniteQueryOptions(
    initialPageParam = 0,
    getNextPageParam = { last, _, _ -> last.nextCursor },
    refetchStrategy = RefetchStrategy.FirstPageOnly,
)
```

**Most REST pagination should use `FirstPageOnly`.** The default is the strict
one because a wrong default here corrupts data rather than merely costing
requests.

## Bounding memory

`maxPages` caps retained pages, bounding both memory and the cost of a refetch:

```kotlin
InfiniteQueryOptions(
    initialPageParam = 0,
    getNextPageParam = { last, _, _ -> last.nextCursor },
    getPreviousPageParam = { first, _, _ -> first.prevCursor },
    maxPages = 5,
)
```

Eviction follows the direction of travel: paging forward drops the oldest pages,
paging backward drops the newest. Supply `getPreviousPageParam` so the window
can move back as well as forward, or scrolling up will find nothing.

## What goes wrong

**A refetch can make the list shorter.** If `getNextPageParam` now returns null
earlier than it used to — the server has fewer pages than before — the trailing
pages are **dropped**, not kept. Keeping them would show records that no longer
exist. Expect page count to change across a refresh.

**Overlapping `fetchNextPage` calls cost one request, not several.** All pages
share one cache entry, and the entry deduplicates in-flight fetches, so a scroll
listener firing three times is harmless. You do not need to guard on
`isFetching` first.

**Retries apply per page.** A flaky page 30 retries page 30 — it does not
re-walk the twenty-nine healthy pages before it. Getting this wrong is a real
failure mode with a real cost, and it gets worse the further a user has
scrolled.

**A page that ultimately fails does not blank the list.** The pages already
loaded survive; the query reports `Error` with `data` intact.

**`flatten` needs to know your page shape.** Kwery cannot guess how to read
items out of a page, so it takes the accessor:

```kotlin
state.data?.flatten { it.items }
```

**Paged is not infinite.** If page 2 should *replace* page 1, do not use this.
Use an ordinary query with the page in the key, plus
[`keepPreviousData()`](paginated-queries.md) so the list does not flash empty on
the way.

## Related

- [Query state](query-state.md) — the status axes, which apply unchanged
- [Caching](caching.md) — when the pages go stale
