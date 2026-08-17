# 10 — Cancellation

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/query-cancellation.md`](../../.reference/tanstack-query/docs/framework/react/guides/query-cancellation.md) |
| **Depends on** | 02 Query functions, 05 Observers |

The feature Kotlin gets closest to for free — and the one where "for free" hides
a sharp edge worth naming.

## TanStack behaviour

An `AbortSignal` is passed into `queryFn` and must be forwarded to `fetch` or
`axios` manually. Queries are cancelled when the last observer unmounts mid-flight,
when `cancelQueries` is called, or when a refetch supersedes an in-flight fetch
(`cancelRefetch`, default `true`).

A cancelled query **reverts to its previous state** rather than entering an
error state. This is important: cancellation is not failure, and treating it as
one would show error UI every time a user navigates away.

## Kwery design

Cancellation is structured concurrency. Nothing needs threading through:

```kotlin
val query = client.query(TodoListKey(filter))   // Flow
// collecting scope cancelled -> observer detaches -> if last, request cancels
```

`cancelQueries(filters)` cancels the in-flight `Deferred` for matching entries
and restores the prior `QueryState`.

### The sharp edge

`CancellationException` is an ordinary `Throwable`, and a `queryFn` containing
`try { … } catch (e: Exception) { … }` will swallow it, breaking cancellation
in a way that is silent and very hard to diagnose. Kwery must not let that
become the user's problem:

- The engine rethrows `CancellationException` before any error handling, so a
  swallowed cancellation cannot enter the retry loop or reach `QueryState.error`.
- The retry engine never counts a `CancellationException` toward `failureCount`
  (see [06](06-retries.md)).
- A lint rule or documented recipe should steer users toward `catch (e: IOException)`
  over `catch (e: Exception)` inside a `QueryFn`.

This is the concrete Kotlin analogue of TanStack's "you must forward the signal"
footgun: less common, but more silent when it happens.

### Bridging non-cancellable clients

```kotlin
QueryFn {
    suspendCancellableCoroutine { cont ->
        val call = okHttp.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        // …
    }
}
```

`QuerySignal` (from [02](02-query-functions.md)) wraps this pattern for clients
that expose only a cancel token.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Cancel on last observer leaving | yes | structured concurrency | planned |
| Manual `cancelQueries` | yes | yes | planned |
| Refetch supersedes in-flight (`cancelRefetch`) | default `true` | same default | planned |
| Cancelled query reverts, not errors | yes | yes | planned |
| Signal passed to fetch fn | `AbortSignal`, manual forwarding | automatic | divergent (better) |
| Bridge for non-cancellable clients | n/a | `QuerySignal` | divergent (addition) |
| Cancellation excluded from retries | implicit | enforced | divergent (better) |
| Leave and return mid-request | cancels, then refetches | joins the in-flight request | divergent (better) |
| Swallowed `CancellationException` | breaks cancellation | contained via `isActive` check | divergent (better) |
| Cancellation gated on the fn consuming the signal | **yes** | no — always cancels at grace expiry | **divergent (see below)** |
| Original exception instance preserved | n/a | yes — no stacktrace-recovered copy | divergent (better) |

### The signal-consumption gate

TanStack only cancels an in-flight fetch when the last observer leaves **if the
query function actually read the `AbortSignal`**. A `queryFn` that ignores the
signal runs to completion and populates the cache; one that reads
`signal.aborted` gets cancelled. Their own tests pin both halves:
`should continue if cancellation is not supported and signal is not consumed`
versus `should not continue when last observer unsubscribed if the signal was
consumed`.

Kotlin has no equivalent gate — a coroutine is cancellable whether or not its
body ever checks `isActive` — so the behaviour cannot be ported as-is.

**Decision: always cancel, at grace expiry.** This is better than either TanStack
branch rather than a compromise between them. The grace window means leaving and
returning quickly **joins the in-flight request** instead of restarting it
(measured: 1 request, not 2), which is the outcome the signal-consumption gate
was approximating. Abandoning for real still cancels, so no work is wasted on a
screen nobody is watching.

The divergence is recorded because a reader porting tests across will notice the
two TanStack cases have no Kwery equivalent.

## Open questions

- **OQ-1.** ~~Cancel immediately on last observer, or let the fetch finish?~~
  **Closed: cancel at grace expiry — already the behaviour C′ produces, and no
  per-query opt-out.**

  The [05](05-deduplication-observers.md) spike measured this directly (S9, S10):
  an in-flight request survives the grace window and is cancelled exactly when it
  expires. Returning to the screen inside the window **joins the existing
  request** rather than restarting it — 1 request, not 2.

  That is better than both options in the original question. TanStack cancels
  immediately, so navigate-away-and-back wastes the first request and makes the
  user wait for a second one. Never cancelling leaks work for users who left for
  good. The grace window already draws the line in the right place, which
  removes the motivation for the `cancelOnLastObserverLeaving` knob entirely —
  no proposed use case survives it.

- **OQ-2.** ~~Custom lint rule for `catch (e: Exception)` in a `QueryFn`?~~
  **Closed: no lint. The failure is contained in the engine instead.**

  Lint would catch the mistake at authoring time but only for users who run it,
  and it costs a whole tooling artifact. Containment is better: after a query
  function returns, the engine checks `coroutineContext.isActive` and treats a
  return from a cancelled scope as cancellation regardless of what the function
  did with the exception.

  So a `QueryFn` that swallows `CancellationException` in a broad catch still
  results in a correctly cancelled query. The bug becomes benign rather than
  silent, which is the outcome lint was wanted for. Documented, and covered by
  the regression test below.

## Definition of done

- [ ] `cancelQueries` implemented; cancellation reverts prior state.
- [ ] Test: cancelled query does **not** enter `Error` status.
- [ ] Test: `CancellationException` thrown inside a `QueryFn` propagates as
      cancellation and does not consume a retry.
- [ ] Test: a `QueryFn` that swallows `CancellationException` in a broad
      `catch (e: Exception)` still results in the query being cancelled — the
      regression test for the sharp edge above.
- [ ] Test: refetch cancels the superseded in-flight fetch by default and does
      not with `cancelRefetch = false`.
- [ ] Test: cancelling one of two observers leaves the shared request running.
