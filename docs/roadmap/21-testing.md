# 21 — Testing Support

| | |
|---|---|
| **Tier** | 3 — v1 integration |
| **Status** | **gate 2 complete** |
| **Module** | `kwery-test` |
| **TanStack source** | [`guides/testing.md`](../../.reference/tanstack-query/docs/framework/react/guides/testing.md) |
| **Depends on** | 04 Caching lifecycle, 07 Refetch triggers, 13 Network mode |

A shipped artifact, not an internal concern. Consumers must be able to test code
built on Kwery without fighting the library, and if that is hard they will
wrap Kwery in their own abstraction and lose most of its value.

## TanStack behaviour

Their guide's advice, which is mostly about disabling the library's helpfulness:

- **Turn off retries in tests.** Otherwise a test asserting an error state waits
  through three exponential backoffs and times out.
- Use a **fresh `QueryClient` per test**; a shared cache leaks state between
  tests and produces order-dependent failures.
- Set `gcTime: Infinity` to stop timers firing during a test run.
- Silence the logger for expected errors.

## Kwery design

The guidance above is real, but a library that requires four pieces of
configuration to be testable has a design problem. `kwery-test` supplies a
client that is already correct:

```kotlin
@Test
fun `shows cached data while refetching`() = runTest {
    // Takes the TestScope: the virtual clock and the cache's coroutine scope
    // both come from it, which is what keeps the cache isolated per test
    // without any teardown.
    val kwery = TestQueryClient(this)

    kwery.client.setQueryData(TodoListKey(All)) { listOf(todo) }
    kwery.settle(10.minutes)            // deterministic staleness, no real delay

    val job = backgroundScope.launch { kwery.query(TodoListKey(All)) { api.todos() }.collect { } }
    kwery.awaitIdle()
    assertEquals(1, kwery.requestCount)
}
```

**Corrected from the original sketch**, which described an API that was never
built: there is no no-argument constructor (the `TestScope` supplies both the
clock and the cache's scope), and the time control is `settle(duration)` rather
than `advanceTimeBy`. `settle` is the honest name — it advances virtual time
*and* runs what became due, which is the part that matters and the part
`advanceUntilIdle` gets wrong for `backgroundScope` coroutines.

`TestQueryClient` defaults: `RetryPolicy.Never`, virtual `TimeSource`,
`TestFocusManager`, `TestOnlineManager`, in-memory persister, and a cache scoped
to the test.

### Controls

```kotlin
kwery.settle(5.minutes)          // drive staleTime / gcTime deterministically
kwery.setOnline(false)           // exercise paused states
kwery.setFocused(false)          // exercise focus refetching
kwery.awaitIdle()                // advance until nothing is fetching or mutating
kwery.recordedRequests           // assert request counts — the dedup test primitive
kwery.requestCount               // …and the count on its own
kwery.requestCountFor(key)       // …per key
kwery.clearRecordedRequests()    // ignore setup traffic
kwery.currentTimeMillis          // the virtual clock
kwery.client                     // the real QueryClient; nothing here is a fake but the environment
```

`recordedRequests` deserves emphasis: nearly every meaningful assertion about a
caching library is "how many requests actually went out?". Deduplication,
staleness, prefetching, rotation behaviour, and the infinite-query refetch
strategies are all request-count assertions, and without a first-class recorder
every consumer builds a counting fake by hand.

`awaitIdle()` replaces the `waitFor`/`eventually` polling that makes async tests
flaky. Because time is virtual it does not *wait* — it advances, so a query
sleeping through a 30-second retry backoff settles instantly.

Two behaviours of it were settled by writing the tests rather than by reasoning:

- **A polling query does not block it.** Between ticks a `refetchInterval` query
  is genuinely idle, so `awaitIdle` returns in the gap. The first version of the
  test assumed the opposite and failed; treating polling as "never idle" would
  have made the control unusable in exactly the tests that need it.
- **It fails loudly rather than hanging.** A fetcher that never completes — most
  often a `CompletableDeferred` the test forgot to complete — throws with that
  named as the likely cause, instead of timing out much later with no clue.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Guidance to disable retries | docs | **default in `TestQueryClient`** | divergent (better) |
| Fresh client per test | docs | default | divergent (better) |
| Neutralise gc timers | `gcTime: Infinity` | virtual clock | divergent (better) |
| Silence expected-error logging | docs | n/a — Kwery logs nothing | divergent |
| Mock offline | devtools toggle | `setOnline(false)` | done |
| Mock focus | manual | `setFocused(false)` | done |
| Deterministic time control | no | `advanceTimeBy` | divergent (better) |
| Request recording | no | `recordedRequests` | divergent (addition) |
| Idle await | `waitFor` polling | `awaitIdle()` | divergent (better) |
| Compose testing recipes | docs | docs + sample | planned |

## Open questions

- **OQ-1.** Should `kwery-test` depend on `kotlinx-coroutines-test`? It is the
  obvious integration point (`TestScope`, `runTest`), but pins consumers to a
  version and couples release cycles. Leaning yes — everyone already uses it, and
  the alternative is reimplementing a virtual clock.
- **OQ-2.** Should `TestQueryClient` fail a test when a query is left fetching at
  the end? It catches a real class of bug (forgotten `awaitIdle`, leaked
  observer) but would be noisy for tests that deliberately assert mid-flight
  states. Leaning: opt-in via `TestQueryClient(strict = true)`.
- **OQ-3.** Should `recordedRequests` capture arguments as well as counts?
  Useful for asserting cursors in infinite-query tests; larger API surface.

## Definition of done

- [x] `TestQueryClient` with all controls implemented and published.
- [ ] Persistence controls (an in-memory persister preconfigured). `kwery-test`
      does not depend on `kwery-persist`, so this would invert the module
      graph — deferred with feature [15](15-persistence.md).
- [x] Kwery's **own** test suite uses it throughout — 166 tests. Dogfooding is
      what found `settle`'s existence: `advanceUntilIdle()` silently reported
      zero requests for work that had never run.
- [x] Test: virtual time drives staleness and gc without real delays, asserted
      on both sides of the boundary.
- [x] Test: `setOnline(false)` produces `Paused` and issues no request;
      reconnecting releases it. `setFocused` likewise drives focus refetching.
- [x] Test: ten concurrent observers of one key record **one** request, while
      three retry attempts record **three**. **Verified by mutation**: recording
      on collection instead of on fetch fails 35 tests across the suite — the
      recorder is load-bearing for nearly every claim Kwery makes.
- [x] Test: `awaitIdle()` returns only once queries **and mutations** have
      settled, advancing virtual time to get there. **Verified by mutation.**
- [x] Test: `awaitIdle()` returns between polling ticks rather than blocking.
- [x] Test: `awaitIdle()` throws a diagnostic on a fetcher that never completes.
- [x] Test: each `TestQueryClient` has its own cache — no cross-test leakage.
- [x] Test: retries are off by default, so an error state is reachable without
      waiting through backoffs. **Verified by mutation.**
- [ ] Documentation page with recipes (gate 3 — `docs/testing.md`).
- [x] Whole Kwery suite runs with no real `delay()` and no flaky retries — 166
      tests in roughly a second.
