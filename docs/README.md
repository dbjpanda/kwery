# Kwery

**Offline-first caching for Android.** Your screens ask for data. Kwery decides
what to serve, what to refresh, and what to queue until the network returns.

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

Here is a normal screen that loads a list. No library, just coroutines:

```kotlin
class TodoViewModel(private val api: Api) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                _state.update { it.copy(isLoading = false, todos = api.todos()) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e) }
            }
        }
    }
}
```

Most Android apps contain some version of this, once per screen. It works, and
it still has all of these problems:

- **Rotate the phone** and it fetches again, even though the data arrived a
  second ago.
- **Two screens showing the same list** make two identical requests at the same
  moment.
- **Reopen the app** after Android kills it and the user gets a blank screen and
  a spinner, every time.
- **Save something with no signal** and the write is simply lost.
- **Pull to refresh** and you cannot tell "loading for the first time" from
  "refreshing what is already on screen", so the whole list flashes a spinner.
- Retry, backoff, and "stop polling when the app is in the background" are all
  still yours to write.

The same screen with Kwery:

```kotlin
val todos = client.query(TodoListKey) { api.todos() }
```

Every item above is handled. Not because the library is clever, but because
these are the same six problems in every app, and they have known answers.

## What you would otherwise write by hand

| What the user notices | What it takes to fix yourself |
|---|---|
| Rotation refetches | keep a cache outside the ViewModel, track what is in flight |
| Two screens, two requests | a registry of in-flight calls keyed by request |
| Spinner on every cold start | a disk cache, plus deciding what is too old to show |
| Lost write when offline | a durable queue, replay on reconnect, idempotency keys |
| Full-screen spinner on refresh | two separate status flags, not one enum |
| Refetch storms on reconnect | debounce, and knowing the network is *really* back |

## Do you need it?

**Probably yes if** your app reads data from a server on more than one screen,
or the same data appears in more than one place, or users use it on a train.

**Probably not if** your app fetches once at startup and never again, or all
your data is local.

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
