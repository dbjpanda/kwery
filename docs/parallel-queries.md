# Parallel and dependent queries

## The problem

A screen usually needs more than one thing. Sometimes those are independent and
should all start at once; sometimes one needs another's result first.

The second case creates a **request waterfall** — two sequential round trips —
which is worth avoiding where the API allows. The first case should be free, and
in Kotlin it is.

## Parallel: nothing to learn

Queries are flows. Collect several and they run concurrently:

```kotlin
combine(
    client.query(ProfileKey(id)) { api.profile(id) },
    client.query(SettingsKey(id)) { api.settings(id) },
) { profile, settings -> profile to settings }
```

Dynamic counts work identically, because a list of flows is just a list:

```kotlin
combine(ids.map { client.query(TodoKey(it)) { api.todo(it) } }) { it.toList() }
```

**There is no `useQueries` equivalent and none is needed.** That API exists in
React purely because hooks cannot be called in a loop or conditionally. Kotlin
has no such rule. Recording this explicitly matters: a mechanical port of
TanStack would have added an API with no reason to exist.

## Dependent: two shapes

When the dependency is a plain value, `enabled` reads most directly:

```kotlin
client.query(
    ProjectsKey(userId),
    QueryOptions(enabled = userId != null),
) { api.projects(userId!!) }
```

When it comes from another query, compose the flows:

```kotlin
client.query(UserKey(email)) { api.user(email) }
    .flatMapLatest { userState ->
        val id = userState.data?.id
        if (id == null) flowOf(QueryState()) else client.query(ProjectsKey(id)) { api.projects(id) }
    }
```

`flatMapLatest` cancels the previous inner query when the key changes, so a
rapidly changing dependency does not leave a trail of subscriptions. Switching
back to a key you used moments ago costs nothing — it lands inside the
[grace window](deduplication.md).

**Prefer avoiding the waterfall.** Two sequential round trips is a real cost on
mobile. If the API can return both in one call, do that; if the second key can
be derived without the first response, [prefetch](prefetching.md) it in
parallel instead.

## One verdict from many queries

`combine` gives a `List<QueryState<T>>`, but a screen needs a single answer: am
I loading, did anything fail, can I render? Writing that by hand is easy to get
subtly wrong.

```kotlin
combine(ids.map { client.query(TodoKey(it)) { api.todo(it) } }) { it.toList() }
    .map { it.aggregate() }
```

`AggregateState<T>` gives you:

| | |
|---|---|
| `data: List<T?>` | one slot per query; null where it has not arrived |
| `status` | `Error` if **any** errored, `Success` if **all** succeeded, else `Pending` |
| `fetchStatus` | `Fetching` if any is; else `Paused` if any is; else `Idle` |
| `error` | the first error encountered |
| `isLoading` | only when every query is loading for the first time |

Each rule was chosen rather than fallen into:

- **A screen cannot claim to be ready while part of it is missing**, so `Success`
  requires all.
- **`Fetching` outranks `Paused`** because something is in fact happening.
- **Partial data is preserved**, so a screen renders what it has instead of
  blanking on one slow query. That is why `data` holds nullable slots rather
  than being null as a whole.

### The disabled-query trap

A disabled query never resolves. Counting it as pending holds the entire screen
in `Pending` **for ever** — almost never what anyone means, so it is excluded by
default:

```kotlin
states.aggregate()                          // disabled queries ignored
states.aggregate(skipDisabled = false)      // …or not, if you mean it
states.aggregate(isDisabled = { myOwnRule(it) })
```

The default heuristic treats "pending and idle" as disabled. Override
`isDisabled` where that is wrong for you.

## Different types on one screen

`aggregate()` handles many queries of the same type. A screen usually needs
several *different* types — a user, their settings, an unread count — and for
that there are typed overloads up to five queries:

```kotlin
combineQueries(
    client.query(UserKey(id)) { api.user(id) },
    client.query(SettingsKey(id)) { api.settings(id) },
) { user, settings -> ScreenState(user, settings) }
```

You get a `Flow<QueryState<ScreenState>>` — one status, one error, one
`dataUpdatedAt` (the freshest of the sources), and no casts.

The transform receives **nullable** data, for the same reason
`AggregateState.data` holds nullable slots: a screen with two of its three
pieces should render what it has rather than blank. Check `isSuccess` on the
result when you need everything present.

Status follows the same rules as `aggregate()`, including the disabled-query
one: a source with `enabled = false` never resolves, so it is excluded rather
than holding the screen in `Pending` for ever.

## What goes wrong

**`combine` waits for every flow to emit at least once.** Each Kwery query emits
its current state immediately, so this is not usually a stall — but a flow of
your own mixed in that emits nothing will hold up the whole combination.

**An empty list aggregates to `Success`.** Nothing failed and nothing is
missing. If "no queries yet" should render differently, check the list before
aggregating.

**`enabled = false` is not the same as a disabled slot in an aggregate.** The
query still exists and still holds cached data; it simply never fetches. It is
`aggregate` that decides to ignore it.

**Prefer `combineQueries` over `aggregate` for mixed types.** `aggregate` is for
many queries of the *same* type; the moment they differ, the typed overloads
below are what you want.

## Related

- [Queries](queries.md) · [Query state](query-state.md)
- [Deduplication](deduplication.md) — why parallel is free
- [Prefetching](prefetching.md) — the waterfall cure
