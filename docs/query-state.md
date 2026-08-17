# Query state

## The problem

Almost every app models a network request as one of three things: loading,
loaded, or failed. That model is wrong in a way you only notice once the app is
in front of users.

A screen showing cached data while refreshing it underneath is **not** loading —
it has data. A screen with nothing to show and no network is **not** loading
either — nothing is happening. A refresh that fails while data is on screen is
not simply "failed"; the content is still there and still useful.

A single enum cannot express any of those, so apps end up bolting flags onto it
until it becomes a struct wearing a costume.

## Two questions, two answers

`QueryState` answers them separately.

| | Values | Question |
|---|---|---|
| `status` | `Pending`, `Error`, `Success` | Do I have data? |
| `fetchStatus` | `Fetching`, `Paused`, `Idle` | Is a request running? |

Every combination is reachable, and the interesting ones are exactly the ones a
single enum loses:

| `status` | `fetchStatus` | What the user sees |
|---|---|---|
| `Pending` | `Fetching` | First load, spinner |
| `Pending` | `Paused` | Cold start with no network — **not** a spinner |
| `Pending` | `Idle` | Disabled query; nothing will happen |
| `Success` | `Fetching` | Content on screen, refreshing underneath |
| `Success` | `Idle` | Settled |
| `Error` | `Idle` | Failed — but `data` may still be there |

## The simplest thing that works

```kotlin
val state = rememberQuery(TodoKey(id)) { api.todo(id) }

when {
    state.isLoading   -> Spinner()
    state.isPaused    -> Text("Waiting for a connection…")
    state.data != null -> TodoView(state.data!!, refreshing = state.isRefreshing)
    state.isError     -> ErrorView(state.error!!)
}
```

Four derived flags cover almost every screen:

| | |
|---|---|
| `isLoading` | `isPending && isFetching` — a first load actually in flight |
| `isRefreshing` | `isSuccess && isFetching` — content on screen, refreshing |
| `isPaused` | waiting for connectivity |
| `isFetching` | a request is running, in any status |

## What goes wrong

**`isPending` is not `isLoading`, and using it for a spinner is the most common
mistake.** A disabled query — `enabled = false`, or one whose input is not ready
yet — sits in `Pending` **forever** without ever fetching. Drive spinners from
`isLoading`, which additionally requires a request to actually be in flight.

**`status == Error` does not mean `data == null`.** Kwery keeps the last
successful data when a refresh fails, because blanking a screen on a transient
network error is worse than showing slightly stale content. So check `data`
before `isError` if you want content to survive failures — which the example
above does deliberately.

**`Pending` + `Paused` is not a loading state.** With no network, a first load
reports `Pending` (no data) and `Paused` (not running). Rendering a spinner
there tells the user something is happening when nothing is, and it will spin
until connectivity returns. It deserves its own message.

**Errors are the instance you threw.** `state.error` is the exact `Throwable`
your query function raised, not a wrapper or a stacktrace-recovered copy, so
`when (val e = state.error) { is HttpException -> … }` behaves as written.

## If you want an exhaustive `when`

The two-axis model costs you a sealed hierarchy. When that matters more than the
states it would lose, project at the UI boundary:

```kotlin
when (val ui = state.toUiState()) {
    QueryUiState.Loading -> Spinner()
    is QueryUiState.Content -> TodoView(ui.data, refreshing = ui.isRefreshing)
    is QueryUiState.Failed  -> ErrorView(ui.error)
}
```

`toUiState()` is deliberately **lossy** — it cannot express every combination —
and that is fine at the edge of the app, where a screen has finitely many
renderings. It is not fine in the cache, which is why `QueryState` is not
modelled that way.

Note `Content` wins over `Failed` when both apply: retained data with an error
renders as content, matching the rule above.

## Related

- [Caching](caching.md) — when data goes stale and when it is evicted
- [Offline writes](offline.md) — what `Paused` means for writes
