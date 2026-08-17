# 08 — Invalidation & Filters

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned |
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
suspend fun QueryClient.invalidateQueries(filters: QueryFilters = QueryFilters())
suspend fun QueryClient.refetchQueries(filters: QueryFilters = QueryFilters())
fun QueryClient.removeQueries(filters: QueryFilters = QueryFilters())
suspend fun QueryClient.cancelQueries(filters: QueryFilters = QueryFilters())
fun QueryClient.resetQueries(filters: QueryFilters = QueryFilters())

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

## Deliberate divergences

1. **Snapshot in `predicate`.** Removes a footgun with no cost.
2. **Typed sugar overloads.** `invalidateQueries(TodoKey("5"))` is exact and
   `invalidateQueries("todos")` is a prefix — the distinction is carried by the
   type rather than an `exact: true` flag, so it cannot be forgotten.

## Open questions

- **OQ-1.** `invalidateQueries()` with no arguments invalidates *everything*.
  That is TanStack's behaviour and a common accident. Should Kwery require an
  explicit `QueryFilters.All` to express it? Leaning yes — make the destructive
  default opt-in, at the cost of a small parity divergence.
- **OQ-2.** Should invalidation of a `Static` query log a warning? It is
  silently ignored today, which is correct but confusing when debugging.

## Definition of done

- [ ] `QueryFilters` and all five client methods implemented.
- [ ] Test for every filter field in isolation and in combination.
- [ ] Test: invalidation refetches active queries immediately and leaves
      inactive ones stale-but-unfetched until observed.
- [ ] Test: data remains visible during an invalidation-triggered refetch.
- [ ] Test: awaiting `invalidateQueries` waits for refetches to settle.
- [ ] Test: `enabled = false` and `StaleTime.Static` queries are skipped.
- [ ] Test: `cancelQueries` aborts in-flight requests without marking them
      failed — the prerequisite for [12](12-optimistic-updates.md).
