# Kwery

<img src="docs/assets/hero.svg" alt="Kwery: async server-state for Android" width="100%">

[![Release](https://jitpack.io/v/dbjpanda/kwery.svg)](https://jitpack.io/#dbjpanda/kwery)
[![CI](https://github.com/dbjpanda/kwery/actions/workflows/ci.yml/badge.svg)](https://github.com/dbjpanda/kwery/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue.svg)](LICENSE)

**Server state for Android.** Kwery decides when your app calls the network,
what to show while it waits, and what to keep when it fails.

Room stores data you own. Kwery manages data someone else owns.

Built on coroutines and Flow. Works from a ViewModel or from Compose.

## Is Kwery another HTTP client?

**No.** You keep Retrofit, Ktor or OkHttp. Kwery sits on top of whichever one
you already use.

```
your screen  ──►  Kwery  ──►  Retrofit / Ktor / OkHttp  ──►  your server
                    │
                    └── decides whether to call the network at all,
                        what to show while it does, and what to keep
```

If you know the web: **Retrofit is `fetch`. Kwery is TanStack Query.** You use
both, and they do different jobs.

Kwery never opens a connection. You hand it a function that fetches, and it
decides when to run that function, who shares the result, what the screen shows
while it runs, and what happens when it fails.

```kotlin
// Kwery handles caching, loading state, dedup, retries and offline.
val state = rememberQuery(TodoKey(id)) {
    api.todo(id)        // your existing Retrofit or Ktor call, unchanged
}
```

## What problem does it actually solve?

Not persistence. Here is the version that already has persistence, a
Room-backed repository, which is what most people reach for:

```kotlin
class TodoRepository(private val api: Api, private val dao: TodoDao) {
    fun todos(): Flow<List<Todo>> = dao.observeAll()      // survives process death

    suspend fun refresh() {
        dao.replaceAll(api.todos())                        // network to database
    }
}
```

That is a good pattern and it fixes the things people assume Kwery is for.
Rotation does not refetch. A cold start after process death shows the last
rows instead of a spinner. It works.

What is still yours to write, per endpoint:

- **When does `refresh()` get called?** On every screen entry? Then you are
  back to a request per launch. Only when the rows are old? You now own a
  freshness policy and a timestamp column.
- **Two screens observe the same list and both call `refresh()`.** Two
  identical requests, in flight at the same moment. The DAO cannot see that;
  it is a database.
- **`refresh()` threw.** You have rows on screen and an error in hand. Is the
  screen in an error state or a success state? One boolean cannot say
  "showing data, and the refresh failed", so the whole list flashes a spinner.
- **The user edited something with no signal.** `dao.update()` succeeded, the
  server never heard about it. You now own a queue, a replay, a retry policy
  and idempotency.
- **The app was backgrounded for two hours.** Refetch on resume? Only if
  stale? Only if the network came back? All yours.

None of that is Room's job. Room is storage and it is good at storage. The
part above it, deciding when to call the network and what the screen says
while you wait, is what Kwery is.

The same screen with Kwery:

```kotlin
val todos = client.query(TodoListKey) { api.todos() }
```

Every item above has an answer, not because the library is clever, but because
these are the same five problems in every app and they have known answers.

## What you still write by hand, with Room already in place

| What the user notices | What it takes to fix yourself |
|---|---|
| A request on every screen entry | a freshness policy, and a timestamp column to drive it |
| Two screens, two identical requests | a registry of calls already in flight, keyed by request |
| Full-screen spinner on pull-to-refresh | two separate status flags, because one enum cannot say "showing data, refresh failed" |
| Lost write when there was no signal | a durable queue, replay on reconnect, idempotency |
| Refetch storms when wifi comes back | debounce, and knowing the network is *really* back |
| Polling that keeps running in the background | tie the interval to process lifecycle |

Kwery's answers to those are `staleTime`, in-flight deduplication, the two
status axes, `OfflineQueue`, `refetchOnReconnect`, and
`refetchIntervalInBackground`.

## Why not just use Room?

Often you should, and it is not either/or. `kwery-persist-room` stores Kwery's
cache *in* Room.

Room plus Flow gives you a reactive local source of truth that survives process
death. If that is all you need, stop reading. You do not need Kwery.

The difference is what each one decides. Room decides nothing about the
network. It stores what you hand it, and every question in the section above,
when to refresh, whether two callers become one request, what the screen says
when a refresh fails while rows are on screen, is still yours to answer once
per endpoint.

Room is the storage. Kwery is the policy above it.

## Do you need it?

**Probably yes if** your app reads data from a server on more than one screen,
or the same data appears in more than one place, or users use it on a train.

**Probably not if** your app fetches once at startup and never again, all your
data is local, or a Room repository with a manual refresh is genuinely enough.

## Install

```kotlin
implementation("io.github.dbjpanda:kwery-core:0.3.2")
implementation("io.github.dbjpanda:kwery-android:0.3.2")        // focus + connectivity
implementation("io.github.dbjpanda:kwery-compose:0.3.2")        // rememberQuery
implementation("io.github.dbjpanda:kwery-persist:0.3.2")        // cache across process death
implementation("io.github.dbjpanda:kwery-persist-room:0.3.2")   // Room store for large caches
testImplementation("io.github.dbjpanda:kwery-test:0.3.2")       // TestQueryClient
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
suite is built on it, and nothing in it calls a real `delay()`, so 398 tests run
in seconds.

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

Inspired by [TanStack Query](https://tanstack.com/query/latest), rebuilt for how
Android behaves: process death, rotation, captive-portal wifi, and a process
that lives for days.

Two Kotlin libraries already cover part of this ground, and both do things
Kwery does not.

**[Store5](https://github.com/MobileNativeFoundation/Store)** is the mature
option. Six years old, 3.4k stars, actively maintained, and Kotlin
Multiplatform across JVM, iOS, Linux and JS. Kwery is Android and JVM only. If
you need one cache shared with an iOS target, Store is the answer and Kwery is
not. The difference that made me not use it: Store wants its stores declared up
front, and I wanted `query(key)` on an arbitrary key at the call site.

**[Soil](https://github.com/soil-kt/soil)** is Compose-Multiplatform-first and
reaches further than queries, adding form and shared-state packs. Its
`soil-query-core` depends on nothing but coroutines, so it works outside
Compose too, and it publishes `soil-query-test`. Targets: Android, JVM, iOS,
JS, WebAssembly. Again more than Kwery.

What Kwery has that neither does, as far as I can tell from their sources: a
durable offline write queue with replay, and two orthogonal status axes so
`success` and `fetching` can be true at once.

And the honest line: **Store has years of production use behind it. Kwery has
four stars and no production users.** That is the strongest argument against
picking Kwery today, and it should be.

## Docs

[Start here](docs/README.md). 23 pages, every code example checked against the
published API on each build.

Popular ones: [queries](docs/queries.md), [caching](docs/caching.md),
[mutations](docs/mutations.md), [offline](docs/offline.md),
[testing](docs/testing.md).

## Status

Version 0.3.2. Early but real: 22 of 24 planned features are specified, tested
and documented. What is left needs a physical device.
[RELEASE.md](RELEASE.md) lists what is missing and why.

Requires JDK 17 to build. Artifacts target JVM 11 and minSdk 24.

## Contributing

Issues and PRs welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Apache 2.0. See [LICENSE](LICENSE).
