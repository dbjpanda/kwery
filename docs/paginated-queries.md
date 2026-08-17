# Paginated queries

## The problem

A paged list — page 2 replaces page 1 — needs no special machinery. It is an
ordinary query whose key contains the page number:

```kotlin
data class TodoPageKey(val page: Int) : QueryKey<List<Todo>> {
    override val parts get() = listOf("todos", page)
}
```

Each page is its own cache entry, which is what you want: going back to page 1
is instant because it is still cached.

There is exactly one problem, and it is a visual one. Page 2 is a *different*
entry, so while it loads there is no data — and the list flashes empty between
pages. On a full-height list that is a layout jump on every tap.

## The simplest thing that works

```kotlin
val page = MutableStateFlow(1)

val todos = page
    .flatMapLatest { p -> client.query(TodoPageKey(p)) { api.todos(p) } }
    .keepPreviousData()
```

While the next page loads, the previous page stays on screen. When the new data
arrives it replaces it.

That is the whole feature. Note it composes as an ordinary `Flow` operator —
there is no paginated query type, because a paginated query is just a query.

## Rendering it

```kotlin
val state by todos.collectAsState(QueryState())

LazyColumn {
    items(state.data ?: emptyList()) { TodoRow(it, dimmed = state.isPlaceholderData) }
}
```

During the transition:

| | |
|---|---|
| `data` | the **previous** page |
| `isPlaceholderData` | `true` |
| `status` | `Success` — there is something on screen |
| `fetchStatus` | `Fetching` |

Dimming on `isPlaceholderData` gives the usual "loading the next page" feel
without a spinner replacing the content.

## What goes wrong

**Errors are not hidden.** If the next page fails, the error is emitted rather
than the previous page being left in place. A stale page concealing a failure is
worse than a gap — the user would believe they were looking at page 2.

**Nothing is written to the cache.** The placeholder exists only in your stream.
Page 2's entry never contains page 1's data, so a second screen reading page 2
directly sees an honest empty entry rather than someone else's leftovers.

**"Previous" belongs to the observer, not the cache.** Two screens paging
independently have different previous values, and the cache has no opinion about
either. That is why this is an operator on your flow rather than an option on
the query.

**Cached data is not placeholder data.** Paging *back* to a page that is still
cached shows it immediately with `isPlaceholderData` false, because it is real
data. Do not dim it.

**This is not infinite scroll.** If pages should accumulate rather than replace,
see [infinite queries](infinite-queries.md).

## Related

- [Infinite queries](infinite-queries.md) — when pages accumulate
- [Query state](query-state.md) — `isPlaceholderData` and the status axes
