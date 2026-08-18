# Prefetching

## The problem

The user taps a row, a screen opens, and a spinner runs for 400 ms. The request
could have started when they tapped — during the transition animation — and the
screen would have rendered with data already in place.

Prefetching is the cheapest perceived-performance win the library offers, and it
costs one line at the call site.

## The three APIs

```kotlin
suspend fun <T> QueryClient.prefetchQuery(key, options, fetcher)      // never throws
suspend fun <T> QueryClient.fetchQuery(key, options, fetcher): T      // throws
suspend fun <T> QueryClient.ensureQueryData(key, options, fetcher): T // cached or fetch
```

They differ in exactly one dimension, and it is the one that matters: **who is
waiting for the result.**

- **`prefetchQuery`** — nobody is waiting. It returns nothing and **never
  throws**, because a speculative fetch failing must not crash a scroll handler
  or surface an error for a screen the user never opened. The failure is still
  recorded in that query's state, so a screen that later observes the key finds
  out normally.
- **`fetchQuery`** — you are waiting. It returns the data and propagates the
  original exception unchanged.
- **`ensureQueryData`** — read-through. Cached data if it is there, a fetch if
  not.

All three respect `staleTime`, which is what makes prefetching safe to over-call.
Prefetching data that is already fresh issues no request at all — five calls in a
row cost one request, or none.

## Android trigger points

The web's hover-and-viewport model does not transfer. The moments worth
prefetching on Android are:

**Navigation intent** — in the click handler, before `navigate()`:

```kotlin
onClick = {
    scope.launch { client.prefetchQuery(TodoKey(id)) { api.todo(id) } }
    navController.navigate("todo/$id")
}
```

The request overlaps the transition animation — typically 200–300 ms of latency
you get for free. Note the `launch`: prefetching must never delay the
navigation.

**Scroll proximity** — prefetch detail data for rows approaching the viewport,
driven by `LazyListState`.

**Deep links** — prefetch while the destination screen is being constructed.

## Infinite queries

```kotlin
client.prefetchInfiniteQuery(FeedKey, feedOptions) { page -> api.feed(page) }
client.ensureInfiniteQueryData(FeedKey, feedOptions, pages = 3) { page -> api.feed(page) }
```

`pages` defaults to **1**, because the destination screen shows one page. Ask
for more only when you know the user will scroll — every extra page is a request
for data nobody has looked at yet. The walk stops early when
`getNextPageParam` returns null, so asking for ten when the source has five
fetches five.

Retry is applied **per page**, exactly as it is for a live infinite query: a
failing page 2 is retried on its own and does not refetch page 1.

## The `gcTime` trap

A prefetched entry has **no observer**, so it is inactive from the moment it
lands, and its `gcTime` countdown starts immediately. Prefetching on app start
for a screen the user reaches two minutes later can be wasted work.

Prefetch close to the point of use. If you genuinely need to prefetch early,
raise `gcTime` for those keys rather than hoping.

This was a real bug, not just a caveat. The gc timer starts when the last
observer *leaves* — and an entry that never had an observer never leaves, so it
never started one. Prefetched entries sat in the cache until LRU eviction, in
precisely the feature that creates the most unobserved entries. Fixed by
scheduling eviction when an unobserved fetch completes, too.

## What goes wrong

**Do not `await` a prefetch on the navigation path.** If you block on it, you
have written a slow navigation, not a fast screen. Use `fetchQuery` when you
genuinely need the value first — and know you are paying for it.

**A prefetch of a key nothing will observe is pure waste.** It costs a request,
cache space, and battery. Prefetch what the user is about to look at, not what
they might.

**Errors are silent by design.** If a prefetch is failing consistently you will
not see it at the prefetch site; you will see it when a screen observes that key.

**Not yet built:** suppression under Data Saver — a user who asked the OS to
restrict background data has not asked for speculative traffic. Tracked in the
roadmap.

## Related

- [Queries](queries.md) · [Caching](caching.md) — `staleTime` and `gcTime`
- [Infinite queries](infinite-queries.md)
