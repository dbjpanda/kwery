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

## Open questions

- **OQ-1.** Should cancelling the last observer of a query *always* cancel the
  request, or should an in-flight fetch be allowed to complete and populate the
  cache? Completing is often what users want — navigate away, come back, data is
  there. TanStack cancels. Kwery could offer `cancelOnLastObserverLeaving = false`
  per query. Worth having; needs a default decision. Leaning: match TanStack's
  cancel-by-default, offer the opt-out.
- **OQ-2.** Is a custom lint rule for `catch (e: Exception)` inside `QueryFn`
  worth the tooling cost, or is documentation enough? Defer to post-v1 and see
  whether it actually bites users.

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
