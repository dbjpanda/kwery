# 19 — Dependent & Parallel Queries

| | |
|---|---|
| **Tier** | 3 — v1 integration |
| **Status** | **gate 2 complete** |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/dependent-queries.md`](../../.reference/tanstack-query/docs/framework/react/guides/dependent-queries.md), [`guides/parallel-queries.md`](../../.reference/tanstack-query/docs/framework/react/guides/parallel-queries.md), [`guides/request-waterfalls.md`](../../.reference/tanstack-query/docs/framework/react/guides/request-waterfalls.md) |
| **Depends on** | 03 Query state |

Composing multiple queries. Largely free in Kotlin, which is why this document
is mostly about what *not* to build.

## TanStack behaviour

**Dependent queries** — a query that needs another's result gates itself with
`enabled`:

```tsx
const { data: user } = useQuery({queryKey: ['user', email], queryFn: getUser})
const { data: projects } = useQuery({
  queryKey: ['projects', user?.id],
  queryFn: getProjects,
  enabled: !!user?.id,      // waits for user
})
```

This creates a **request waterfall** — two sequential round trips — which the
docs flag as a performance concern to avoid where the API allows.

**Parallel queries** — several `useQuery` calls in one component run
concurrently automatically. When the *number* of queries is dynamic, hooks rules
forbid calling `useQuery` in a loop, so `useQueries` exists solely to work
around that React constraint.

## Kwery design

Both fall out of coroutines, and the second problem does not exist at all.

**Dependent:**

```kotlin
val projects = client.query(UserKey(email)) { api.user(email) }
    .flatMapLatest { userState ->
        val id = userState.data?.id
        if (id == null) flowOf(QueryState())
        else client.query(ProjectsKey(id)) { api.projects(id) }
    }
```

Or, where the dependency is a plain value, `enabled` reads more directly and
matches TanStack:

```kotlin
client.query(ProjectsKey(userId), enabled = userId != null) { api.projects(userId!!) }
```

**Parallel** — `combine` over any number of query flows, static or dynamic:

```kotlin
combine(ids.map { client.query(TodoKey(it)) { api.todo(it) } }) { states ->
    states.toList()
}
```

**`useQueries` has no Kwery equivalent and needs none.** It exists purely because
React forbids conditional or looped hook calls. Kotlin has no such rule — a
`List<Flow<QueryState<T>>>` is just a list. Recording this explicitly matters,
because a mechanical port would have added an API with no reason to exist.

### The aggregate-state problem

`combine` gives a `List<QueryState<T>>`, but a screen needs one answer: am I
loading, did anything fail, do I have everything? Every consumer writes this and
it is easy to get subtly wrong (e.g. treating "any pending" as loading when one
query is disabled and will never resolve).

```kotlin
fun <T> List<QueryState<T>>.aggregate(): QueryState<List<T>>
fun combineQueryStates(vararg states: QueryState<*>): AggregateState
```

Semantics, chosen deliberately:

- `status` is `Error` if **any** errored (first error wins), `Success` if **all**
  succeeded, otherwise `Pending`.
- `fetchStatus` is `Fetching` if any is fetching; `Paused` if any is paused and
  none fetching; otherwise `Idle`.
- Partial data is preserved so a screen can render what it has.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Dependent via `enabled` | yes | `enabled` param | planned |
| Dependent via composition | n/a | `flatMapLatest` | divergent (addition) |
| Static parallel queries | automatic | automatic | planned |
| Dynamic parallel queries | `useQueries` | `combine` over a list | divergent (simpler) |
| `combine` result aggregation | `useQueries({combine})` | `aggregate()` | planned |
| Waterfall avoidance guidance | docs | docs | planned |
| Aggregate loading/error semantics | manual | `aggregate()` | divergent (addition) |

## Open questions

- **OQ-1.** ~~Disabled query: satisfied, or pending for ever?~~ **Closed:
  excluded by default.** One disabled query would otherwise hold an entire
  screen in `Pending` indefinitely, which is almost never what anyone means.
  `skipDisabled = false` opts out, and the predicate identifying a disabled
  query is overridable for cases the default heuristic gets wrong.
- **OQ-2.** Should there be a typed `combine` for heterogeneous queries, e.g.
  `combineQueries(userQ, settingsQ) { user, settings -> ScreenState(user, settings) }`?
  Requires arity overloads up to some N, which is boilerplate in the library but
  removes casts for consumers. Probably worth it up to 5.

## Definition of done

- [x] `aggregate()` implemented, returning an `AggregateState`.
- [ ] Typed `combineQueries(a, b) { … }` arity overloads (OQ-2).
- [ ] Test: dependent query ordering end to end (covered in principle by
      `enabled` and `flatMapLatest`, both already tested elsewhere).
- [x] Test: `flatMapLatest` over a changing key cancels the old observer
      (`KeepPreviousDataTest`).
- [x] Test: N observers across N keys issue N concurrent requests
      (`CacheLifecycleTest`, 100 keys).
- [x] Test: aggregate status across all-success, partial, one-error and empty.
- [x] Test: `Fetching` outranks `Paused` — something IS happening.
- [x] Test: partial data is preserved so a screen renders what it has.
- [x] Test: a disabled query does not hold the aggregate in `Pending`, and
      `skipDisabled = false` opts out.
- [ ] Documentation section on avoiding waterfalls, with a prefetch cross-link
      to [20](20-prefetching.md).
