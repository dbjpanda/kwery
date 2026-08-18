# Retries

## The problem

Mobile networks fail in a way desktop networks mostly do not: a request dies
because the user walked into a lift, and the identical request succeeds four
seconds later. Retrying is not papering over a bug — on Android it is the normal
case.

But retrying the wrong thing is worse than not retrying. A `404` will still be a
`404` in eight seconds, and retrying a failed payment can charge someone twice.

## The simplest thing that works

Queries retry three times by default. Mutations do not retry at all.

```kotlin
client.query(TodoKey(id), QueryOptions(retry = RetryPolicy.Times(3))) { api.todo(id) }
```

| | |
|---|---|
| `RetryPolicy.Never` | give up on the first failure |
| `RetryPolicy.Times(n)` | up to `n` retries after the initial attempt |
| `RetryPolicy.Forever` | keep trying — pair with a bounded delay |
| `RetryPolicy.Decide { attempt, error -> … }` | decide per failure |
| `RetryPolicy.Default` | `Times(3)` — the query default |
| `RetryPolicy.ForMutations` | `Never` — the mutation default |

**Queries and mutations have different defaults because they are different
risks.** A query is a read: running it twice costs a round trip. A mutation is a
write, and the library cannot know whether yours is idempotent — so it never
guesses. Opt in per mutation, once you know.

## Not retrying what cannot succeed

`Times(3)` on an HTTP client retries 404s and 401s, which is three wasted round
trips and three seconds of a spinner the user did not need to see:

```kotlin
val policy = RetryPolicy.Times(3).exceptWhen { error ->
    error is HttpException && error.code in 400..499
}
```

`exceptWhen` wraps any policy with a predicate that stops it dead. The
formulation matters: it says *when to stop*, so the interesting condition is the
one you write, not the one you have to remember to leave out.

## Delay

The default is **exponential backoff with equal jitter**, capped at 30 seconds:

```kotlin
RetryDelay.Default                        // equal jitter, the default
RetryDelay.exponential                    // 1s, 2s, 4s, 8s … no jitter
RetryDelay.constant(2.seconds)
RetryDelay.equalJitter(RetryDelay.exponential)
```

**Jitter is not a detail.** Without it, every client that failed during an
outage retries at the same instant, and the recovering server is knocked over by
its own users — a thundering herd. Equal jitter waits a random duration in
`[half, full]` of the backoff, keeping most of the backoff's growth while
spreading the arrivals.

The exponent saturates rather than overflowing. Under `Forever`, attempt 40 is
not a negative delay or a crash; it is the cap.

## What goes wrong

**Cancellation is not a failure.** If a user leaves a screen mid-request, the
coroutine is cancelled — and a retry loop that treats that as an error would
resurrect a request nobody is waiting for. Kwery never retries a
`CancellationException`, and this guard is load-bearing: without it, a cancelled
`Forever` query kept going for 251 attempts before the test gave up.

For the same reason, do not `catch (e: Exception)` inside a query function.
`CancellationException` is an `Exception`.

**Retries are invisible in `status`.** A retrying query stays `Pending` /
`Fetching` — `isRetrying` tells you it is on a second or later attempt, which is
what you want for "still trying…" copy. It does not go `Error` until every
attempt is spent.

**Under `Forever`, cap the delay.** `Forever` with unbounded exponential backoff
eventually waits hours. `Forever` is for something the user is watching that
must eventually connect, so pair it with `constant` or accept the 30-second cap.

**Retries and offline are separate mechanisms.** Under `NetworkMode.Online` a
query with no connectivity does not fail-then-retry; it **pauses** and consumes
no attempts. Retries handle a failing network, `Paused` handles an absent one.
See [offline](offline.md).

## Related

- [Queries](queries.md) · [Mutations](mutations.md) · [Offline](offline.md)
