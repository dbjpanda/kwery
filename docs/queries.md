# Queries

## The problem

Fetching data is easy. Everything around it is not: knowing when what you have
is too old, not fetching the same thing twice because two screens want it,
keeping a list on screen while it refreshes, and not starting over every time
the device rotates.

A query is a declaration that a screen depends on some server data. Kwery owns
the rest.

## The simplest thing that works

```kotlin
// A key names the data it produces, so nothing downstream needs a cast.
data class TodoKey(val id: String) : QueryKey<Todo> {
    override val parts get() = listOf("todo", id)
}

// From a ViewModel
val todo: StateFlow<QueryState<Todo>> =
    client.query(TodoKey(id)) { api.todo(id) }
        .stateIn(viewModelScope, WhileSubscribed(5_000), QueryState())

// From Compose
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

The returned `Flow` is **cold**. Collecting it attaches an observer; cancelling
the collector detaches one. Two collectors of the same key share one cache entry
and one in-flight request.

## The query function

A plain `suspend () -> T`. It returns data or **throws**:

```kotlin
client.query(TodoKey(id)) { api.todo(id) }
```

No context object, no cancellation token to thread through. Structured
concurrency handles cancellation, and the call site already holds whatever a
context would have carried — the key was just built from the same values.

**Callback-based clients need no special support.** If your client hands back
an abort handle rather than suspending, bridge it once at the call site:

```kotlin
client.query(TodoKey(id)) {
    suspendCancellableCoroutine { continuation ->
        val call = api.enqueue(id) { continuation.resume(it) }
        continuation.invokeOnCancellation { call.cancel() }
    }
}
```

Leaving the screen then aborts the request — though not instantly, and
deliberately so: an in-flight fetch belongs to the cache entry rather than to
whichever screen started it, so a rotation does not kill a request it is about
to want back, and one screen closing does not abort a fetch another is sharing.
The abort happens once the grace window closes with nothing observing.

One caveat worth stating: **do not catch `Exception` in here.**
`CancellationException` is an `Exception`, so a broad catch swallows
cancellation. Kwery makes sure a swallowed cancellation cannot fabricate a
success, but it is still confusing to debug. Catch what you mean —
`IOException`, your HTTP client's exception type.

## Options that matter early

```kotlin
client.query(
    key = TodoKey(id),
    options = QueryOptions(
        staleTime = StaleTime.of(30.seconds),
        enabled = id.isNotEmpty(),
        retry = RetryPolicy.Times(3),
    ),
) { api.todo(id) }
```

| | Default | |
|---|---|---|
| `staleTime` | `Zero` | how long data is fresh — see [caching](caching.md) |
| `gcTime` | 5 minutes | how long unwatched data is kept |
| `enabled` | `true` | false disables the query entirely |
| `retry` | 3 attempts | see [retries](retries.md) |
| `networkMode` | `Online` | pause rather than fail when offline |

**`enabled = false`** is how a query waits for its input. It holds whatever is
cached, never fetches automatically, and ignores invalidation:

```kotlin
client.query(TodoKey(id), QueryOptions(enabled = id != null)) { api.todo(id!!) }
```

## Deriving a narrower value

```kotlin
client.query(TodoListKey, select = { it?.count { todo -> !todo.done } ?: 0 }) {
    api.todos()
}
```

The projection is deduplicated, so a screen watching a count only recomposes
when the count changes — not when an unrelated field of the list does.

## What goes wrong

**`isPending` is not `isLoading`.** A disabled query sits in `Pending` for ever
without fetching. Drive spinners from `isLoading`, which also requires a request
to be in flight. This is the single most common mistake — see
[query state](query-state.md).

**Two screens asking for the same key share everything**, including status. If
one triggers a refetch, the other sees `isRefreshing` too. That is the point,
but it surprises people the first time.

**Rotation does not refetch.** A detach and reattach within the grace window
(5 seconds) counts as the same mount. Without this the default `staleTime = Zero`
would fire a redundant request on every rotation.

**Errors keep their data.** A failed refresh leaves the previous data in place,
so `status == Error` does not mean `data == null`.

**Your exception arrives unchanged.** `state.error` is the instance your query
function threw, not a wrapper or a stacktrace-recovered copy, so type checks on
it behave as written.

## Related

- [Query keys](query-keys.md) — identity, and why keys are typed
- [Query state](query-state.md) — the two status axes
- [Caching](caching.md) — staleness and eviction
- [Retries](retries.md) · [Refetching](refetching.md) · [Mutations](mutations.md)
