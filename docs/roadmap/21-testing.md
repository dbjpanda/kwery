# 21 — Testing Support

| | |
|---|---|
| **Tier** | 3 — v1 integration |
| **Status** | planned |
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
    val client = TestQueryClient()      // no retries, virtual clock, controllable
                                        //  focus/connectivity, isolated cache

    client.setQueryData(TodoListKey(All), listOf(todo))
    client.advanceTimeBy(10.minutes)    // deterministic staleness, no real delay

    val state = client.query(TodoListKey(All)) { api.todos() }.first()
    assertTrue(state.isRefreshing)
}
```

`TestQueryClient` defaults: `RetryPolicy.Never`, virtual `TimeSource`,
`TestFocusManager`, `TestOnlineManager`, in-memory persister, and a cache scoped
to the test.

### Controls

```kotlin
client.advanceTimeBy(5.minutes)      // drive staleTime / gcTime deterministically
client.setOnline(false)              // exercise paused states
client.setFocused(false)             // exercise focus refetching
client.awaitIdle()                   // suspend until no query is fetching
client.recordedRequests              // assert request counts — the dedup test primitive
```

`recordedRequests` deserves emphasis: nearly every meaningful assertion about a
caching library is "how many requests actually went out?". Deduplication,
staleness, prefetching, rotation behaviour, and the infinite-query refetch
strategies are all request-count assertions, and without a first-class recorder
every consumer builds a counting fake by hand.

`awaitIdle()` replaces the `waitFor`/`eventually` polling that makes async tests
flaky.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Guidance to disable retries | docs | **default in `TestQueryClient`** | divergent (better) |
| Fresh client per test | docs | default | divergent (better) |
| Neutralise gc timers | `gcTime: Infinity` | virtual clock | divergent (better) |
| Silence expected-error logging | docs | default | planned |
| Mock offline | devtools toggle | `setOnline(false)` | planned |
| Mock focus | manual | `setFocused(false)` | planned |
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

- [ ] `TestQueryClient` with all controls implemented and published.
- [ ] Kwery's **own** test suite uses it — dogfooding is the real validation.
- [ ] Test: `advanceTimeBy` drives staleness and gc without real delays.
- [ ] Test: `setOnline(false)` produces `FetchStatus.Paused`.
- [ ] Test: `recordedRequests` correctly counts deduplicated requests as one.
- [ ] Test: `awaitIdle()` returns only when all queries have settled.
- [ ] Documentation page with recipes for ViewModel tests, Compose UI tests, and
      repository tests.
- [ ] Whole Kwery suite runs with no real `delay()` and no flaky retries.
