# 20 — Prefetching

| | |
|---|---|
| **Tier** | 3 — v1 integration |
| **Status** | planned |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/prefetching.md`](../../.reference/tanstack-query/docs/framework/react/guides/prefetching.md), [`guides/request-waterfalls.md`](../../.reference/tanstack-query/docs/framework/react/guides/request-waterfalls.md) |
| **Depends on** | 04 Caching lifecycle, 09 Manual cache |

Fetch before the user asks, so the destination screen renders with data already
present. The cheapest perceived-performance win the library offers.

## TanStack behaviour

- `prefetchQuery(options)` — fetch and cache; **never throws** and returns no
  data, since nothing is observing it.
- `prefetchInfiniteQuery` — same for infinite queries.
- `fetchQuery` — like prefetch but **returns the data and does throw**; use when
  the result is needed imperatively.
- `ensureQueryData` — returns cached data if present, otherwise fetches.
- Respects `staleTime`: prefetching fresh data is a no-op, so calling it on every
  hover or scroll is safe.
- A prefetched entry with no observer is immediately inactive, so its `gcTime`
  timer starts right away. A prefetch too far ahead of navigation can be
  garbage-collected before it is used.

## Kwery design

```kotlin
suspend fun <T> QueryClient.prefetchQuery(
    key: QueryKey<T>,
    staleTime: StaleTime = StaleTime.of(Duration.ZERO),
    queryFn: QueryFn<T>,
)                                                   // never throws

suspend fun <T> QueryClient.fetchQuery(key: QueryKey<T>, queryFn: QueryFn<T>): T   // throws
suspend fun <T> QueryClient.ensureQueryData(key: QueryKey<T>, queryFn: QueryFn<T>): T
```

That `prefetchQuery` swallows errors is deliberate and matches TanStack: a
speculative fetch failing must never surface to the user or crash a scroll
handler. It logs at debug level so failures remain diagnosable.

### Android prefetch triggers

The moments worth prefetching differ from the web's hover-and-viewport model:

- **Navigation intent** — prefetch in the click handler before `navigate()`, so
  the request overlaps the transition animation. Usually 200–300 ms of free
  latency.
- **List scroll proximity** — prefetch detail data for rows approaching the
  viewport, via `LazyListState`.
- **Deep-link handling** — prefetch while the destination screen is being built.

The `gcTime` interaction is a real footgun worth documenting: prefetching on app
start for a screen the user reaches two minutes later is wasted work under the
default 5-minute `gcTime` if anything evicts it sooner. Prefetch close to the
point of use, or raise `gcTime` for those keys.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `prefetchQuery` | yes | yes | planned |
| `prefetchQuery` never throws | yes | yes | planned |
| `prefetchInfiniteQuery` | yes | yes | planned |
| `fetchQuery` (returns, throws) | yes | yes | planned |
| `ensureQueryData` | yes | yes | planned |
| `ensureInfiniteQueryData` | yes | yes | planned |
| Respects `staleTime` (safe to over-call) | yes | yes | planned |
| Router-integrated prefetch | framework routers | Navigation Compose recipe | planned |
| Viewport/scroll prefetch helper | no | `prefetchNearViewport` | divergent (addition) |
| SSR/streaming prefetch | yes | non-goal | divergent (gap) |

## Open questions

- **OQ-1.** Should prefetches be deprioritised relative to observed queries? A
  burst of scroll-triggered prefetches can starve the fetch the user is actually
  waiting for. This implies a priority-aware dispatcher in the core, which is
  meaningful complexity. Leaning: v1 ships without it, but the fetch path should
  take a `QueryPriority` parameter from the start so adding it later is not a
  breaking change.
- **OQ-2.** Should prefetched entries get a separate, longer `gcTime` by
  default? It would mitigate the footgun above, but silently diverging cache
  behaviour based on how an entry arrived is hard to reason about. Leaning no;
  document instead.
- **OQ-3.** Should prefetching be suppressed on metered connections by default?
  Speculative work is exactly what a user on a capped plan does not want. Ties
  to [13](13-network-mode.md) OQ-1.

## Definition of done

- [ ] `prefetchQuery`, `prefetchInfiniteQuery`, `fetchQuery`,
      `ensureQueryData`, `ensureInfiniteQueryData` implemented.
- [ ] Test: `prefetchQuery` swallows a thrown error and leaves the cache clean.
- [ ] Test: `fetchQuery` propagates the same error.
- [ ] Test: prefetching fresh data within `staleTime` issues no request.
- [ ] Test: `ensureQueryData` returns cached data without a request, and fetches
      when absent.
- [ ] Test: a prefetched entry is immediately inactive and starts its `gcTime`.
- [ ] Test: a query observing a prefetched key gets data with no second request.
- [ ] `QueryPriority` parameter present in the fetch path even if unused (OQ-1).
- [ ] Navigation Compose prefetch recipe in the sample app.
