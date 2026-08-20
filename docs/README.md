# Kwery

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

The same cache from a ViewModel:

```kotlin
val todos = client.query(TodoListKey) { api.todos() }
    .stateIn(viewModelScope, WhileSubscribed(5_000), QueryState())
```

Compose is a thin layer over the Flow core. Both surfaces share one cache entry
and one in-flight request.

## Where to go next

<div class="grid cards" markdown>

-   :material-rocket-launch:{ .lg .middle } **Start here**

    ---

    Read data, give it identity, and understand the two status axes that
    every other page assumes.

    [:octicons-arrow-right-24: Queries](queries.md)

-   :material-refresh:{ .lg .middle } **Keeping data fresh**

    ---

    Deduplication, the two clocks, invalidation, retries, and the four
    automatic refetch triggers.

    [:octicons-arrow-right-24: Caching](caching.md)

-   :material-pencil:{ .lg .middle } **Writing**

    ---

    Mutations, optimistic updates, and writes that survive process death.

    [:octicons-arrow-right-24: Mutations](mutations.md)

-   :material-android:{ .lg .middle } **In your app**

    ---

    Compose bindings, the ViewModel pattern, testing, and inspecting the
    cache.

    [:octicons-arrow-right-24: Compose](compose.md)

</div>

## All pages

**Start here**

- [Queries](queries.md) — reading data, and everything the library does around it
- [Query keys](query-keys.md) — identity, and why keys are typed
- [Query state](query-state.md) — the two status axes, and why one enum is not enough

**Keeping data fresh**

- [Deduplication and observers](deduplication.md) — sharing, the grace window, eviction
- [Caching](caching.md) — staleTime, gcTime, and why they are different clocks
- [Invalidation](invalidation.md) — making writes visible to reads
- [Reading and writing the cache](manual-cache.md) — the escape hatches, and when not to reach for them
- [Retries](retries.md) — what to retry, what never to retry, and jitter
- [Cancellation](cancellation.md) — what happens when a screen goes away
- [Refetching](refetching.md) — the four automatic triggers
- [Prefetching](prefetching.md) — starting the request before the screen opens

**Writing**

- [Mutations](mutations.md) — writes, their lifecycle, and scopes
- [Optimistic updates](optimistic-updates.md) — showing a write before it lands
- [Offline writes](offline.md) — durable writes that survive process death
- [Persistence](persistence.md) — the query cache across process death

**Using it from your app**

- [Compose](compose.md) — `rememberQuery` and friends
- [ViewModels](viewmodels.md) — the `stateIn` pattern, measured
- [Testing](testing.md) — `TestQueryClient`, and why request counts are the assertion
- [Inspecting the cache](devtools.md) — snapshots, and why a query refetched

**Composing queries**

- [Parallel and dependent queries](parallel-queries.md) — `combine`, `aggregate`, and avoiding waterfalls

**Lists**

- [Infinite queries](infinite-queries.md) — accumulating pages
- [Paginated queries](paginated-queries.md) — pages that replace each other

## Two things everyone gets wrong

Worth reading before anything else, because they cause most of the confusion:

- **`staleTime` and `gcTime` are different clocks.** One decides when to
  refetch, the other when to forget. See [caching](caching.md).
- **`status` and `fetchStatus` are separate axes.** Collapsing them loses
  "showing data while refreshing in the background" and "waiting, offline",
  which are the states an Android app actually spends its time in. See
  [query state](query-state.md).

---

Kwery is open source under Apache 2.0. Issues, source and contributing guide
are on [GitHub](https://github.com/dbjpanda/kwery).
