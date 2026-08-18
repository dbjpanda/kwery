# ViewModels

## The problem

A large share of production Android apps put server state in a ViewModel, not in
a composable. Kwery has to work well there — not as a fallback, but as a
first-class way to use it.

The complication is that `stateIn(scope, SharingStarted.WhileSubscribed(5_000))`
and Kwery's own 5-second observer grace window are **two independent timeouts
stacked on each other**. This page says what that stack actually does, in
measured request counts rather than reassurance.

## The pattern

```kotlin
class TodoViewModel(
    private val client: QueryClient,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val filter = savedState.getStateFlow("filter", TodoFilter.All)

    val todos: StateFlow<QueryState<List<Todo>>> =
        filter
            .flatMapLatest { client.query(TodoListKey(it)) { api.todos(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueryState())

    fun setFilter(value: TodoFilter) { savedState["filter"] = value }

    fun refresh() = viewModelScope.launch {
        client.invalidateQueries(TodoListKey(filter.value))
    }
}
```

Nothing here is Kwery ceremony — it is the standard Android pattern, and that is
the point. There is no `useQueries` equivalent and none is needed: a
`List<Flow<QueryState<T>>>` is just a list, so `combine` handles any number of
parallel queries, static or dynamic.

## What actually happens

Every row below is asserted by a test, not inferred.

| Event | Requests | Cache |
|---|---|---|
| Rotation (×5) | **0 extra** | entry kept |
| Filter changes | 1 per new key, in order | both kept |
| Filter changes back within the grace window | **0** | served warm |
| Backstack — screen closed | 0 | evicted once, after `WhileSubscribed` → grace → `gcTime` |
| Process death | — | gone unless [persisted](persistence.md) |

**Rotation costs nothing** because `WhileSubscribed(5_000)` keeps the upstream
alive across the configuration change — the cache never even sees a detach. The
rotation problem exists in *direct* collection (`rememberQuery`), and is solved
there by the grace window.

**The stacking is not worth worrying about.** Measured: 310 s from screen close
to eviction with the grace layer, versus 305 s without. A 1.6 % difference, so
plain `WhileSubscribed` is correct and no custom `SharingStarted` exists.

## Choosing a `SharingStarted`

| | Use it? |
|---|---|
| `WhileSubscribed(5_000)` | **Yes.** The 5 s matches Kwery's grace window and survives rotation. |
| `WhileSubscribed()` (no timeout) | Works, but drops the subscription instantly on rotation, so you lean on the grace window alone. |
| `Eagerly` | Fetches before anything is on screen. Occasionally right; usually a wasted request. |
| `Lazily` | **No.** See below. |

### `Lazily` leaks

`Lazily` starts on the first collector and **never stops**. Kwery therefore never
sees a detach, `gcTime` never starts, and the entry lives as long as the
ViewModel. Measured: still cached and still counted as observed 30 minutes after
the last collector went away.

Kwery does not warn about this, deliberately. The cache cannot tell a leaked
collector from a screen the user has genuinely had open all afternoon — both are
one observer that never detaches — and a warning that fires on correct code is
worse than none. Instead the data is available:

```kotlin
client.cacheSnapshot()
    .filter { it.isActive }
    .forEach { println("${it.key} observed for ${now - it.observedSinceMillis!!}ms") }
```

`observedSinceMillis` tracks the *first* observer, so a second screen opening on
the same key does not reset it and hide the leak.

## `SavedStateHandle`: keys yes, data no

Put query **keys** in saved state. Never query **data**.

```kotlin
private val filter = savedState.getStateFlow("filter", TodoFilter.All)  // ✅ a key input
```

`SavedStateHandle` is written to a `Bundle` and crosses a Binder transaction,
which has a hard limit around 1 MB **shared across the whole transaction**. A
cached list of API responses will exceed it, and the failure is a
`TransactionTooLargeException` crash at an unrelated moment — on backgrounding,
on a device you were not testing.

Data that must survive process death belongs in [persistence](persistence.md),
which is built for it.

## Testing

```kotlin
@Test
fun `rotation costs no requests`() = runTest {
    val kwery = TestQueryClient(this)
    val todos = kwery.query(TodoListKey("all")) { api.todos() }
        .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), QueryState())

    val collector = backgroundScope.launch { todos.collect { } }
    kwery.awaitIdle()
    assertEquals(1, kwery.requestCount)

    collector.cancel()                       // rotation away
    kwery.settle(200.milliseconds)
    backgroundScope.launch { todos.collect { } }   // …and back
    kwery.awaitIdle()

    assertEquals(1, kwery.requestCount)
}
```

See [testing](testing.md).

## What goes wrong

**Do not hold a `QueryState` in a `var`.** The flow is the source of truth;
copying its latest value into a field gives you two, and they disagree during a
refetch.

**One client per app, not per ViewModel.** The cache is the shared thing. A
client per ViewModel means no deduplication and no shared data between screens —
which is most of what the library is for.

**`viewModelScope` is right for `stateIn`, but not for `invalidateQueries`
inside a mutation's `onSettled`.** The client's own scope owns that work; a
ViewModel cleared mid-write would cancel it.

## Related

- [Queries](queries.md) · [Caching](caching.md) · [Invalidation](invalidation.md)
- [Compose](compose.md) — the same core, a different surface
- [Persistence](persistence.md) · [Testing](testing.md)
