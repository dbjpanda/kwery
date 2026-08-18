# Kwery

<img src="docs/assets/hero.svg" alt="Kwery: async server-state for Android" width="100%">

[![Release](https://jitpack.io/v/dbjpanda/kwery.svg)](https://jitpack.io/#dbjpanda/kwery)
[![CI](https://github.com/dbjpanda/kwery/actions/workflows/ci.yml/badge.svg)](https://github.com/dbjpanda/kwery/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue.svg)](LICENSE)

Server state for Android, done properly. Caching, deduplication, background
refresh, offline writes, and a cache that survives process death.

If you know [TanStack Query](https://tanstack.com/query/latest), you know Kwery.

## Install

```kotlin
implementation("io.github.dbjpanda:kwery-core:0.2.1")
implementation("io.github.dbjpanda:kwery-android:0.2.1")   // focus + connectivity
implementation("io.github.dbjpanda:kwery-compose:0.2.1")   // rememberQuery
implementation("io.github.dbjpanda:kwery-persist:0.2.1")   // cache across process death
testImplementation("io.github.dbjpanda:kwery-test:0.2.1")  // TestQueryClient
```

On Maven Central. No extra repository needed.

## How it works

<img src="docs/assets/flow.svg" alt="Two screens share one cache entry and one request" width="100%">

## Use it

Declare a key. Ask for data. That is the whole API.

```kotlin
data class TodoKey(val id: String) : QueryKey<Todo> {
    override val parts get() = listOf("todo", id)
}

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

Same cache from a ViewModel:

```kotlin
val todos = client.query(TodoListKey) { api.todos() }
    .stateIn(viewModelScope, WhileSubscribed(5_000), QueryState())
```

Compose is a thin layer over the Flow core. Both surfaces share one entry and
one in-flight request.

## Why not roll your own

Most apps hand-roll this in a repository class. It works until the third screen
wants the same data, or the phone rotates mid-request, or someone taps save on
the Tube.

Kwery handles the Android cases that are easy to get wrong:

| Situation | What happens |
|---|---|
| Two screens, same key | One request, shared state |
| Rotate the phone | Zero extra requests |
| Return to the app | Refetch only if stale |
| Captive portal wifi | Treated as offline, not a failure |
| No network | Query pauses, keeps data on screen |
| Write while offline | Queued, replayed after process death |
| Long session | Cache bounded by size, not just time |

Each row is backed by a test. The rotation one is why the grace window exists:
without it, the default `staleTime` of zero fires a fresh request every time the
device turns.

## Two status axes

Most libraries use one enum. That loses the two states Android hits most.

```kotlin
state.isRefreshing   // success + fetching: new data on the way, old data on screen
state.isPaused       // pending + paused: offline, will resume by itself
```

`status` says what you have. `fetchStatus` says what is happening. Keeping them
apart is what makes a background refresh different from a cold start.

## Testing

`TestQueryClient` gives you a virtual clock and request counting. Kwery's own
370 tests use it. None of them call a real `delay()`.

```kotlin
@Test fun `deduplicates`() = runTest {
    val kwery = TestQueryClient(this)

    repeat(10) {
        backgroundScope.launch { kwery.query(TodoKey("1")) { api.todo() }.collect { } }
    }
    kwery.awaitIdle()

    assertEquals(1, kwery.requestCount)   // ten screens, one request
}
```

## Compared to

|  | Kwery | [Soil](https://github.com/soil-kt/soil) | [Store5](https://github.com/MobileNativeFoundation/Store) |
|---|---|---|---|
| Works in a ViewModel | yes | Compose only | yes |
| Ad-hoc `query(key)` | yes | yes | stores declared up front |
| Cache survives process death | built in | no | via `SourceOfTruth` |
| Offline write queue | yes | no | partial |
| Virtual-clock test client | yes | no | no |

## Docs

[Start here](docs/README.md). 22 pages, every code example checked against the
published API on each build.

Popular ones: [queries](docs/queries.md), [caching](docs/caching.md),
[mutations](docs/mutations.md), [offline](docs/offline.md),
[testing](docs/testing.md).

## Status

Version 0.2.1. Early but real: 19 of 24 planned features are specified, tested
and documented. [RELEASE.md](RELEASE.md) lists what is missing and why.

Requires JDK 17 to build. Artifacts target JVM 11 and minSdk 24.

## Contributing

Issues and PRs welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Apache 2.0. See [LICENSE](LICENSE).
