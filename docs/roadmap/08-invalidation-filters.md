# 08 — Invalidation & Filters

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | **gate 2 complete** |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/query-invalidation.md`](../../.reference/tanstack-query/docs/framework/react/guides/query-invalidation.md), [`guides/filters.md`](../../.reference/tanstack-query/docs/framework/react/guides/filters.md), [`guides/invalidations-from-mutations.md`](../../.reference/tanstack-query/docs/framework/react/guides/invalidations-from-mutations.md) |
| **Depends on** | 01 Query keys |

Invalidation is how writes become visible. `invalidateQueries` is the single
most-called method in a real TanStack app.

## TanStack behaviour

`invalidateQueries(filters)` marks matching queries stale and refetches the
**active** ones immediately; inactive ones refetch when next observed. It does
not evict — data stays on screen while the refetch runs, which is the whole
point of stale-while-revalidate.

`QueryFilters` fields: `queryKey` (prefix match by default), `exact`,
`type` (`active` | `inactive` | `all`, default `all`), `stale`, `fetchStatus`,
and `predicate` as a final filter.

Related client methods sharing the same filter type: `refetchQueries` (refetch
regardless of staleness), `removeQueries` (evict outright, no refetch),
`cancelQueries` (abort in flight — the prerequisite for safe optimistic
updates), `resetQueries` (back to initial state).

## Kwery design

```kotlin
data class QueryFilters(
    val keyPrefix: List<Any?>? = null,
    val exactKey: QueryKey<*>? = null,
    val type: QueryType = QueryType.All,
    val stale: Boolean? = null,
    val fetchStatus: FetchStatus? = null,
    val predicate: ((QueryEntrySnapshot) -> Boolean)? = null,
)

enum class QueryType { Active, Inactive, All }
```

```kotlin
// No default argument — invalidating everything must be spelled out (OQ-1).
suspend fun QueryClient.invalidateQueries(filters: QueryFilters)
suspend fun QueryClient.refetchQueries(filters: QueryFilters)
fun QueryClient.removeQueries(filters: QueryFilters)
suspend fun QueryClient.cancelQueries(filters: QueryFilters)
fun QueryClient.resetQueries(filters: QueryFilters)

// sugar
suspend fun QueryClient.invalidateQueries(key: QueryKey<*>)      // exact
suspend fun QueryClient.invalidateQueries(vararg prefix: Any?)   // prefix
```

`suspend` on the refetching variants preserves an important TanStack property:
awaiting `invalidateQueries` inside a mutation's `onSettled` keeps the mutation
`pending` until refetches finish, so the UI does not flash "done" before the
list updates.

`predicate` receives a `QueryEntrySnapshot` — an immutable view — rather than
the live entry. TanStack hands over the mutable `Query` object, which invites
predicates that mutate cache state as a side effect.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `invalidateQueries` marks stale + refetches active | yes | yes | planned |
| Prefix matching by default | yes | `keyPrefix` | planned |
| `exact` | yes | `exactKey` | planned |
| `type: active/inactive/all` | yes | `QueryType` | planned |
| `stale` filter | yes | yes | planned |
| `fetchStatus` filter | yes | yes | planned |
| `predicate` | yes | yes, over an immutable snapshot | divergent (safer) |
| `refetchQueries` | yes | yes | planned |
| `removeQueries` | yes | yes | planned |
| `cancelQueries` | yes | yes | planned |
| `resetQueries` | yes | yes | planned |
| Awaiting invalidation keeps mutation pending | yes | `suspend` | planned |
| Skips queries with `enabled = false` | yes | yes | planned |
| Skips `staleTime: 'static'` queries | yes | yes | planned |
| `matchQuery` / `matchMutation` utilities | yes | `QueryFilters.matches(…)` | planned |
| No-arg call invalidates everything | yes | **rejected** — requires `QueryFilters.All` | divergent (better) |

## Deliberate divergences

1. **Snapshot in `predicate`.** Removes a footgun with no cost.
2. **Typed sugar overloads.** `invalidateQueries(TodoKey("5"))` is exact and
   `invalidateQueries("todos")` is a prefix — the distinction is carried by the
   type rather than an `exact: true` flag, so it cannot be forgotten.

## Open questions

- **OQ-1.** ~~Should the no-argument form be allowed to invalidate everything?~~
  **Closed: no. `QueryFilters.All` must be passed explicitly.**

  In TanStack, `invalidateQueries()` invalidates the entire cache. The intended
  meaning at almost every call site is "the thing I just changed", and the
  no-argument form reads exactly like that while doing something far broader. It
  is a footgun whose cost — refetching every query in the app — is invisible in
  development and expensive on cellular.

  ```kotlin
  client.invalidateQueries(TodoKey("5"))        // exact
  client.invalidateQueries("todos")             // prefix
  client.invalidateQueries(QueryFilters.All)    // everything, and it says so
  ```

  The destructive operation costs four extra characters and is now impossible to
  perform by accident. This is a deliberate parity divergence.

- **OQ-2.** ~~Warn when invalidating a `Static` query?~~ **Closed: no runtime
  warning.** Any broad prefix invalidation will legitimately match `Static`
  entries as a matter of course, so a warning would fire on correct code — the
  same failure mode that killed the `gcTime` warning in
  [04](04-caching-lifecycle.md) OQ-1. The information is genuinely useful when
  debugging, so it is surfaced as a **reason on the devtools event stream**
  ([22](22-devtools.md)) rather than as log noise.

### `invalidateQueries` did not actually await

`suspend` on the refetching methods was specified so that awaiting them keeps a
mutation `Pending` until the list has refreshed — the behaviour
`docs/mutations.md` documents and the reason
`onSettled = { invalidateQueries(...) }` is useful at all.

It did not work. `invalidate()` started the refetch and returned; the `suspend`
modifier was doing nothing. Found by writing the test for it, and fixed by
returning the in-flight fetch so the client can await them all.

A failed refetch is swallowed rather than propagated: it is already reflected in
that query's own state, and letting it escape would surface an unrelated
query's failure at the `invalidateQueries` call site.

**Verified by mutation**: reverting to fire-and-forget fails the test.

## Definition of done

- [x] `QueryFilters` and all five client methods implemented.
- [x] Tests for key prefix, exact key, `type`, `stale`, `fetchStatus` and
      `predicate`, in isolation and combined.
- [x] Test: invalidation refetches active queries immediately and leaves
      inactive ones stale-but-unfetched until observed again.
- [x] Test: data remains visible during an invalidation-triggered refetch.
- [x] Test: awaiting `invalidateQueries` waits for refetches to settle.
      **This was broken and the test found it** — see below.
- [x] Test: `enabled = false` and `StaleTime.Static` queries are skipped.
- [x] Test: `cancelQueries` aborts in-flight requests without marking them
      failed — the prerequisite for [12](12-optimistic-updates.md).
- [x] Test: `removeQueries` evicts without refetching; `resetQueries` returns
      an entry to its initial state.
