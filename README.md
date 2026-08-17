# Kwery

Async server-state management for Android and Kotlin — caching, deduplication,
stale-while-revalidate, mutations, offline support, and a cache that survives
process death.

A [TanStack Query](https://tanstack.com/query/latest) equivalent for Kotlin,
built for how Android actually behaves.

> **Status: pre-alpha, not yet released.** The design is specified and decided;
> implementation is in progress. Nothing is published to Maven Central yet.
> See [the roadmap](docs/roadmap/README.md) for what is built and what is not.

## Why

Android already has good pieces — Retrofit, Ktor, Room, coroutines — but nothing
that owns *server state* as a concern: knowing when cached data is stale, sharing
one request between two screens that ask for the same thing, refetching when the
app comes back to the foreground, keeping a list on screen while it refreshes
underneath, and not losing a write because the process was killed.

The existing options each stop short:

|  | Kwery | [Soil](https://github.com/soil-kt/soil) | [Store5](https://github.com/MobileNativeFoundation/Store) |
|---|---|---|---|
| Usable from a ViewModel | yes | no — Compose only | yes |
| Ad-hoc `query(key)` from any call site | yes | yes | no — stores declared up front |
| Persisted cache across process death | first-class | no | via `SourceOfTruth` |
| Offline mutation queue | yes | no | partial |

## What it looks like

```kotlin
// A key names its own data type, so nothing downstream needs a cast.
data class TodoKey(val id: String) : QueryKey<Todo> {
    override val parts get() = listOf("todo", id)
}

// From a ViewModel
val todo: StateFlow<QueryState<Todo>> =
    client.query(TodoKey(id)) { api.todo(id) }
        .stateIn(viewModelScope, WhileSubscribed(5_000), QueryState())

// Or from Compose
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

Two things in that snippet are load-bearing and worth knowing early:

- **`status` and `fetchStatus` are separate.** `status` answers *do I have
  data?*; `fetchStatus` answers *is a request running?* A screen showing cached
  data while refreshing is `success` + `fetching`; a cold start with no network
  is `pending` + `paused`. One enum cannot express either.
- **`isLoading` is not `isPending`.** `isLoading` is `isPending && isFetching` —
  the flag that should drive a spinner. A disabled query sits in `pending`
  forever without ever fetching.

## Design

Four decisions the whole library rests on:

- **JVM-pure core.** `kwery-core` has no Android dependencies; Android concerns
  enter through interfaces implemented in `kwery-android`. The cache's
  time-dependent logic is unit-testable against a virtual clock, with no
  Robolectric and no device.
- **Flow-first.** The core primitive is `Flow<QueryState<T>>`. Compose is a thin
  adapter over it, not a parallel implementation — so ViewModels are a
  first-class consumer rather than an afterthought.
- **Typed query keys.** A key declares the type it produces, making
  `getQueryData`/`setQueryData`/`select` type-safe and making TanStack's most
  common bug — a query function reading a variable absent from the key — fail to
  compile.
- **Parity by measurement, not by memory.** TanStack Query's docs *and*
  `query-core` test suite are vendored at a pinned revision under
  `.reference/`. Every parity claim cites it, and behaviour is ported from their
  tests rather than from recollection.

Full rationale, including 16 deliberate divergences and why each exists, is in
[docs/roadmap/README.md](docs/roadmap/README.md).

## Building

```sh
./gradlew build
```

Requires JDK 17 to build; the published artifacts target JVM 11.

## Contributing

Kwery uses a three-gate workflow — spec, then tests, then documentation, in that
order and never out of it. See [CLAUDE.md](CLAUDE.md) for the rules and
[docs/roadmap/README.md](docs/roadmap/README.md) for current status.

## Licence

[Apache 2.0](LICENSE). See [NOTICE](NOTICE) for third-party attribution.
