# Testing

## The problem

Testing a cache is mostly testing what *did not* happen: the second request that
was deduplicated, the refetch that rotation did not cause, the prefetch that was
a no-op because the data was fresh. None of that is visible in final state — you
have to count requests.

The other problem is time. `staleTime` and `gcTime` are measured in minutes, and
a suite that waits real minutes gets skipped.

`kwery-test` solves both. Add it as a test dependency:

```kotlin
testImplementation("dev.kwery:kwery-test:<version>")
```

## The simplest thing that works

```kotlin
@Test
fun `deduplicates concurrent observers`() = runTest {
    val kwery = TestQueryClient(this)

    val jobs = List(10) {
        backgroundScope.launch { kwery.query(TodoKey("1")) { api.todo("1") }.collect { } }
    }
    kwery.awaitIdle()

    assertEquals(1, kwery.requestCount)   // ten screens, one request
    jobs.forEach { it.cancel() }
}
```

`TestQueryClient` takes the `TestScope` — it supplies both the virtual clock and
the cache's coroutine scope, which is what keeps each test's cache isolated with
no teardown. It arrives already configured for tests: **retries off**, virtual
time, controllable focus and connectivity, request recording.

That last point matters. The usual advice is largely four pieces of
configuration you must remember (disable retries, fresh client, neutralise gc
timers, silence logging). A library that needs that much setup to be testable
has a design problem, so the defaults are simply correct here.

## The controls

```kotlin
kwery.client                  // the real QueryClient — nothing is faked but the environment
kwery.query(key) { … }        // like client.query, but records every fetch

kwery.settle()                // run everything currently due
kwery.settle(5.minutes)       // …and advance virtual time first
kwery.awaitIdle()             // advance until nothing is fetching or mutating
kwery.currentTimeMillis       // the virtual clock

kwery.setOnline(false)        // exercise Paused states
kwery.setFocused(false)       // exercise focus refetching

kwery.requestCount            // assertions live here
kwery.recordedRequests        // …in order, including retries
kwery.requestCountFor(key)    // …per key
kwery.clearRecordedRequests() // ignore setup traffic
```

Use `kwery.query(...)` rather than `kwery.client.query(...)` in tests — only the
former records.

### `settle` vs `awaitIdle`

`settle()` runs what is due right now; `settle(duration)` advances the clock
first. Use it when you are asserting about a *specific* moment — "four minutes
in, still fresh".

`awaitIdle()` advances until nothing is in flight. Use it when you just want the
work finished. Because time is virtual it does not wait — a query sleeping
through a 30-second backoff settles instantly.

**Do not use `advanceUntilIdle()`.** It does not dispatch coroutines launched in
`backgroundScope`, which is where observers live, so it silently reports zero
requests for work that never ran. That mistake cost a day of debugging during
Kwery's own development, which is why `settle` exists.

## Recipes

**Staleness and gc, without waiting:**

```kotlin
val options = QueryOptions(staleTime = StaleTime.of(5.minutes))
val job = backgroundScope.launch { kwery.query(key, options) { api.todo() }.collect { } }
kwery.awaitIdle()

kwery.settle(6.minutes)
kwery.client.invalidateQueries(QueryFilters(exactKey = key, stale = true))
kwery.awaitIdle()
assertEquals(2, kwery.requestCount)
```

**Offline behaviour:**

```kotlin
kwery.setOnline(false)
val job = backgroundScope.launch { kwery.query(key) { api.todo() }.collect { state = it } }
kwery.settle()

assertEquals(FetchStatus.Paused, state.fetchStatus)  // paused, not failed
assertEquals(0, kwery.requestCount)

kwery.setOnline(true)
kwery.awaitIdle()
assertEquals(1, kwery.requestCount)
```

**A ViewModel:** see [viewmodels](viewmodels.md#testing) — collect the
`StateFlow` in `backgroundScope` and assert request counts across the lifecycle
events you care about.

**A mutation:**

```kotlin
val m = kwery.client.mutation(
    MutationOptions<String, Todo, Unit>(mutationFn = { api.create(it) }),
)
m.mutate("new")
kwery.awaitIdle()
assertEquals(MutationStatus.Success, m.state.value.status)
```

## What goes wrong

**Assert request counts, not just final state.** Most meaningful claims about a
caching library are request-count claims. A test that only checks `data` passes
whether the cache worked or not.

**Cancel your collectors.** `backgroundScope` cleans up at the end of the test,
but a collector left running through a `settle(10.minutes)` keeps the entry
alive and changes what you are measuring.

**`awaitIdle` throws rather than hanging.** If a fetcher never completes — most
often a `CompletableDeferred` the test forgot to complete — it fails with that
named as the likely cause after 10 virtual minutes, instead of timing out much
later with no clue.

**A polling query does not block `awaitIdle`.** Between ticks it is genuinely
idle, so `awaitIdle` returns in the gap. To assert about polling, use
`settle(duration)` and count.

**Retries are off by default.** Turn them on per query when that is what you are
testing; leaving them on globally makes every error test slow.

**Each `TestQueryClient` is isolated.** Two in one test share nothing — useful
for testing that they *don't*, and a reason you never need teardown.

## Related

- [Queries](queries.md) · [ViewModels](viewmodels.md) · [Compose](compose.md)
- [Caching](caching.md) — what the time controls are actually driving
