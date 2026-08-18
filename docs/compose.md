# Compose

## The problem

A screen needs data, and it needs to stop needing it when the user leaves.
Getting that wrong is how apps leak requests: a query restarted on every frame,
or one that keeps polling a screen nobody is looking at.

`kwery-compose` is a thin adapter over the same `Flow` surface a ViewModel uses.
It adds no behaviour of its own — if something works here and not from a
ViewModel, that is a bug in Kwery, not a feature of the bindings.

## Setup

Provide the client once, near the root:

```kotlin
CompositionLocalProvider(LocalQueryClient provides client) {
    App()
}
```

Everything below reads it from there.

## Queries

```kotlin
@Composable
fun TodoScreen(id: String) {
    val state = rememberQuery(TodoKey(id)) { api.todo(id) }
    when {
        state.isLoading -> Spinner()
        state.isError   -> ErrorView(state.error!!)
        else            -> TodoView(state.data!!, refreshing = state.isRefreshing)
    }
}
```

Two things it handles so you do not have to:

- **The key drives resubscription, and nothing else does.** `QueryKey`
  implementations are data classes, so an equal key across recompositions is the
  same subscription. This is the Compose analogue of React's dependency array,
  and it needs no memoisation from you.
- **The fetcher is captured, not observed.** A lambda is allocated fresh on
  every recomposition; treating it as an input would resubscribe every frame. It
  is read through `rememberUpdatedState`, so the latest lambda always runs
  without ever being a reason to resubscribe.

Leaving the composition detaches the observer. Re-entering within the grace
window — a rotation, a brief navigation — counts as the same mount, so it
neither refetches nor evicts.

For a narrower projection:

```kotlin
val openCount by rememberQuerySelecting(
    TodoListKey,
    select = { it?.count { todo -> !todo.done } ?: 0 },
) { api.todos() }
```

Deduplicated, so a badge showing a count only recomposes when the count changes.

## Mutations

```kotlin
@Composable
fun AddTodoButton() {
    val mutation = rememberMutation {
        MutationOptions<String, Todo, Unit>(mutationFn = { api.create(it) })
    }
    val state by (mutation?.stateAsState() ?: return)

    Button(
        onClick = { mutation.mutate("new todo") },
        enabled = state.status != MutationStatus.Pending,
    ) { Text("Add") }
}
```

**`rememberMutation` returns null on the first frame.** Creating a mutation
acquires its scope lock, which suspends, and nothing may suspend during
composition. Rather than hand back a half-built object or block the frame, the
binding returns null until it is ready — one frame, in practice. Handle it with
an early return or `?.`; do not `!!` it.

The `key` parameter controls when the mutation is rebuilt, the same way
`remember(key)` does. It defaults to `Unit`, meaning "build once".

## Infinite lists

```kotlin
@Composable
fun TodoList() {
    val query = rememberInfiniteQuery(
        TodoPagesKey,
        InfiniteQueryOptions(
            initialPageParam = 1,
            // (lastPage, allPages, lastPageParam) -> next param, or null when
            // there are no more. All three are given because "is there a next
            // page?" is answered differently by different APIs — a cursor in
            // the response, a total count, or simply a short final page.
            getNextPageParam = { lastPage, _, lastParam ->
                if (lastPage.isEmpty()) null else lastParam + 1
            },
        ),
    ) { page -> api.todos(page) }

    val state by query.stateAsState()
    val listState = rememberLazyListState()

    LazyColumn(state = listState) {
        items(state.data?.pages?.flatten().orEmpty()) { TodoRow(it) }
    }

    query.FetchNextPageWhenNearEnd(listState)
}
```

`FetchNextPageWhenNearEnd` fetches when the user gets within `threshold` items
of the end — 3 for lists, 6 for grids, since a grid row shows several items at
once. There is a `LazyGridState` overload with the same shape.

Fetching ahead of the scroll rather than at the very end is what makes paging
feel seamless instead of stuttering. It is driven by `derivedStateOf`, so it
does not recompose on every scroll pixel.

## Global indicators

```kotlin
val fetching by rememberIsFetching()   // Int: queries in flight
val saving by rememberIsMutating()     // Int: writes in flight, queued ones included
val restoring by rememberIsRestoring() // Boolean: cache being restored from disk
```

For a progress bar in a toolbar, not for a screen's own state. A background
refetch of data already on screen belongs here and **not** in the screen's
loading branch — covering readable data with a spinner is the classic misuse.

## What goes wrong

**`isPending` is not `isLoading`.** A disabled query sits in `Pending` forever
without ever fetching. Drive spinners from `isLoading`, which also requires a
request in flight.

**Do not build a `QueryKey` with unstable values.** `TodoKey(id)` is fine — data
class equality does the work. A key built from a fresh list or lambda each
recomposition is a different key every frame and will resubscribe every frame.

**A recomposition storm costs no requests.** Verified under the most hostile
settings available: no grace window, `refetchOnMount = Always`, ten
recompositions, one request.

**Rendering paths are not yet covered by tests on a device.** The composition
behaviour above is tested headlessly; the loading/error/refreshing *render*
paths are tracked as device-only work in the roadmap.

## Related

- [Queries](queries.md) · [Query state](query-state.md) · [Mutations](mutations.md)
- [Infinite queries](infinite-queries.md) · [ViewModels](viewmodels.md)
