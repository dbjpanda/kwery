# Kwery

Async server-state management for Android and Kotlin — caching, deduplication,
stale-while-revalidate, mutations, offline support, and a cache that survives
process death.

A [TanStack Query](https://tanstack.com/query/latest) equivalent for Kotlin,
built for how Android actually behaves.

> **Status: pre-alpha, not yet released.** 19 of 24 features are specified,
> tested and documented; nothing is published to Maven Central yet.
> See [RELEASE.md](RELEASE.md) for exactly what is built, what was deliberately
> not built and why, and what still needs a device.

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
- **Parity by measurement, not by memory.** Every parity claim cites TanStack
  Query's own docs or `query-core` test suite at a pinned revision, and
  behaviour is ported from their tests rather than from recollection. That
  material is fetched locally by contributors rather than committed — see
  [CONTRIBUTING.md](CONTRIBUTING.md).

The deliberate divergences from TanStack, and the reason for each, are listed
in [RELEASE.md](RELEASE.md#deliberately-not-built). The reasoning behind the
observer model in particular — including the measurements that chose it — is in
[docs/deduplication.md](docs/deduplication.md).

## Building

```sh
./scripts/vendor-reference.sh   # once, before test work
./gradlew build
```

Requires JDK 17 to build; the published artifacts target JVM 11. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## Contributing

Kwery uses a three-gate workflow — spec, then tests, then documentation, in that
order and never out of it. See [CONTRIBUTING.md](CONTRIBUTING.md) to get set up,
and [RELEASE.md](RELEASE.md) for current status.

## Licence

[Apache 2.0](LICENSE). See [NOTICE](NOTICE) for third-party attribution.
