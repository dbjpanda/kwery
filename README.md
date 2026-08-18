# Kwery

<img src="docs/assets/hero.svg" alt="Kwery — async server-state for Android" width="100%">

[![Release](https://jitpack.io/v/dbjpanda/kwery.svg)](https://jitpack.io/#dbjpanda/kwery)
[![CI](https://github.com/dbjpanda/kwery/actions/workflows/ci.yml/badge.svg)](https://github.com/dbjpanda/kwery/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue.svg)](LICENSE)

Async server-state management for Android and Kotlin — caching, deduplication,
stale-while-revalidate, mutations, offline support, and a cache that survives
process death.

A [TanStack Query](https://tanstack.com/query/latest) equivalent for Kotlin,
built for how Android actually behaves.

> **Status: `0.1.1`, early but real.** 19 of 24 features are specified, tested
> and documented, with 367 tests behind them. On Maven Central.
> See [RELEASE.md](RELEASE.md) for exactly what is built, what was deliberately
> not built and why, and what still needs a device.

## Why

Android already has good pieces — Retrofit, Ktor, Room, coroutines — but nothing
that owns *server state* as a concern: knowing when cached data is stale, sharing
one request between two screens that ask for the same thing, refetching when the
app returns to the foreground, keeping a list on screen while it refreshes
underneath, and not losing a write because the process was killed.

Most apps end up hand-rolling this in a repository layer. It works until the
third screen wants the same data, or the user rotates the phone mid-request, or
they tap save on the Tube.

### Built for how Android actually behaves

These are the differences that matter, and each was **measured rather than
assumed** — the numbers are in [`docs/`](docs/):

- **Rotation costs zero requests.** A reattach inside a five-second grace window
  is a continuation, not a new mount, so it skips the staleness check. Without
  that rule, the default `staleTime = 0` fires a redundant request every single
  time the device turns — measured, not theorised.
- **"Online" means a *validated* network.** A captive portal reports a perfectly
  good connection on which every request fails. Treating connected as online
  means refetching into a black hole and showing errors instead of the paused
  state the user should see.
- **Focus is process lifecycle, not Activity focus.** Per-Activity focus fires on
  dialogs, permission prompts and the app switcher — a refetch storm dressed up
  as a feature.
- **The cache is bounded by count as well as time.** A browser tab gets reloaded;
  an Android process can live for days.
- **Offline is a state, not an error.** Queries *pause* and keep their data on
  screen; writes queue durably and replay after process death.

### Two status axes, because one is not enough

`status` (pending/error/success) and `fetchStatus` (fetching/paused/idle) stay
separate, so `success` + `fetching` — a background refresh over data already on
screen — and `pending` + `paused` — a cold start with no network — are both
expressible. Collapsing them into one enum loses exactly the states Android
hits most.

### Written to be testable

`kwery-test` ships a `TestQueryClient` with a virtual clock and request
recording, because nearly every meaningful claim about a cache is a
request-count claim: *deduplicated*, *did not refetch*, *the prefetch was a
no-op*. Kwery's own 370-odd tests use it, and none of them call a real
`delay()` — the suite runs in seconds.

```kotlin
val kwery = TestQueryClient(this)
repeat(10) { backgroundScope.launch { kwery.query(TodoKey("1")) { api.todo() }.collect { } } }
kwery.awaitIdle()
assertEquals(1, kwery.requestCount)   // ten screens, one request
```

### Against the alternatives

|  | Kwery | [Soil](https://github.com/soil-kt/soil) | [Store5](https://github.com/MobileNativeFoundation/Store) |
|---|---|---|---|
| Usable from a ViewModel | yes | no — Compose only | yes |
| Ad-hoc `query(key)` from any call site | yes | yes | no — stores declared up front |
| Persisted cache across process death | first-class | no | via `SourceOfTruth` |
| Offline mutation queue | yes | no | partial |
| Two orthogonal status axes | yes | partial | no |
| Virtual-clock test client | yes | no | no |

The Flow-first core is the wedge: `kwery-compose` is a thin adapter over it, so
the same cache serves a ViewModel and a composable identically. If a behaviour
existed only in the Compose layer, that would be a bug.

### What it is not

It is **new** — `0.2.1`, not battle-tested across a hundred apps. 19 of 24
planned features are through spec, tests and documentation;
[RELEASE.md](RELEASE.md) lists exactly what is done, what was deliberately not
built and why, and what still needs a device. Nothing there is hidden, because
finding out later is worse than knowing now.

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

## Installing

```kotlin
dependencies {
    implementation("io.github.dbjpanda:kwery-core:0.1.1")
    implementation("io.github.dbjpanda:kwery-android:0.1.1")   // focus + connectivity
    implementation("io.github.dbjpanda:kwery-compose:0.1.1")   // rememberQuery
    implementation("io.github.dbjpanda:kwery-persist:0.1.1")   // cache across process death
    testImplementation("io.github.dbjpanda:kwery-test:0.1.1")  // TestQueryClient
}
```

On Maven Central, so `mavenCentral()` is all you need. The badge at the top
shows the current version; the block above may lag it by a release.

Snapshots are not published. To try unreleased work, JitPack builds any branch
or tag straight from this repository — add `maven("https://jitpack.io")` and
depend on `com.github.dbjpanda.kwery:kwery-core:main-SNAPSHOT`. Note the
coordinates differ: JitPack serves under `com.github.dbjpanda.kwery`, Maven
Central under `io.github.dbjpanda`.

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
and [RELEASE.md](RELEASE.md) for what each module is ready for.

## Licence

[Apache 2.0](LICENSE). See [NOTICE](NOTICE) for third-party attribution.
