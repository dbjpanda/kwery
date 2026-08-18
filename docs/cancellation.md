# Cancellation

## The problem

A user opens a screen, waits half a second, and leaves. The request is still in
flight. What should happen?

Getting this wrong shows up in two ways: requests that keep running for screens
nobody is looking at, or — worse — a cancellation reported as an error, so the
next visitor to that screen sees a failure that never happened.

## You do not call anything

Cancellation is structural. A query is collected from a coroutine; when that
coroutine is cancelled, the collector detaches. There is no token to thread
through and no `dispose()` to remember.

```kotlin
val job = scope.launch { client.query(TodoKey(id)) { api.todo(id) }.collect { } }
job.cancel()   // that is the whole API
```

In Compose and in a ViewModel this is already handled: leaving the composition
or clearing the ViewModel cancels the collector.

## What actually happens to the request

**Not what you might expect, and deliberately.** Cancelling a collector does
*not* abort the in-flight request immediately.

An in-flight fetch belongs to the **cache entry**, not to whichever collector
started it. Aborting on detach would:

- kill a request a rotation is about to want back, and
- abort a fetch a second screen is still sharing.

So the request keeps running, and is aborted only once the grace window closes
with nothing observing. A screen closed for good stops its work; a screen that
turns sideways does not.

## Cancellation is not failure

A cancelled query does not enter `Error`. Nothing failed — the user left.

This matters beyond tidiness: an error state would be **cached**, so the next
screen to observe that key would open showing a failure caused by someone else's
navigation. Kwery leaves no error behind for the next observer, and the
retry machinery treats `CancellationException` as a non-event rather than an
attempt to burn.

That guard is load-bearing: without it, a cancelled query under
`RetryPolicy.Forever` kept retrying 251 times before the test gave up.

## Cancelling explicitly

```kotlin
client.cancelQueries(QueryFilters(exactKey = TodoKey("5")))
client.cancelQueries(QueryFilters(keyPrefix = listOf("todos")))
```

The main use is before an [optimistic update](optimistic-updates.md): a refetch
that resolves *after* you write an optimistic value would overwrite it with
stale server data. `optimisticMutation` does this for you.

Cancelling a query that has already settled is a no-op, not an error.

## What goes wrong

**Do not catch `Exception` in a query function.** `CancellationException` is an
`Exception`, so a broad catch swallows cancellation and makes a cancelled
coroutine look like a successful one. Kwery contains the damage — a fetcher that
swallows it still cannot fabricate a success, because it runs inside a cancelled
`async` — but the behaviour is confusing to debug. Catch what you mean.

**A refetch does not race the fetch it replaces.** Requesting a refetch while
one is in flight supersedes it rather than running both and taking whichever
returns first.

**Cancelling one of several observers changes nothing.** The others are still
watching; the request continues and they all receive the result.

## Related

- [Queries](queries.md) — including bridging a callback-based client
- [Deduplication](deduplication.md) — why the entry owns the request
- [Optimistic updates](optimistic-updates.md) · [Retries](retries.md)
