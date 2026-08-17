# 06 — Retries & Backoff

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/query-retries.md`](../../.reference/tanstack-query/docs/framework/react/guides/query-retries.md) |
| **Blocks** | 13 Network mode |

## TanStack behaviour

- Queries retry **3 times** by default. Mutations retry **0** times by default —
  an asymmetry that matters, because a retried non-idempotent write can
  double-charge a customer.
- `retry` accepts `false`, a number, `true` (infinite), or
  `(failureCount, error) => boolean`. `failureCount` starts at `0` for the first
  retry attempt.
- Default `retryDelay` is `min(1000 * 2 ** attemptIndex, 30_000)` — exponential
  from 1 s, capped at 30 s.
- `retryDelay` may be a constant instead of a function.
- During retries the error is exposed as **`failureReason`**, not `error`.
  `error` is only populated after the final attempt fails. This lets a UI show
  "retrying, last error was X" without entering an error state.
- Retries respect focus: with `refetchIntervalInBackground`, retries pause when
  the tab is inactive.

## Kwery design

```kotlin
sealed interface RetryPolicy {
    data object Never : RetryPolicy
    data class Times(val count: Int) : RetryPolicy
    data object Forever : RetryPolicy
    fun interface Decide : RetryPolicy {
        /** [failureCount] starts at 0 for the first retry decision. */
        fun shouldRetry(failureCount: Int, error: Throwable): Boolean
    }

    companion object {
        val Default: RetryPolicy = Times(3)
        val ForMutations: RetryPolicy = Never
    }
}

fun interface RetryDelay {
    fun delayFor(attemptIndex: Int, error: Throwable): Duration

    companion object {
        /** min(1s * 2^attempt, 30s) — matches TanStack. */
        val Exponential = RetryDelay { attempt, _ ->
            minOf(1.seconds * (1L shl attempt), 30.seconds)
        }
    }
}
```

A sealed interface rather than TanStack's overloaded `boolean | number |
function` union: Kotlin has no union types, and `RetryPolicy.Forever` reads
better than `retry = true` anyway.

### Non-retryable errors

TanStack pushes this into the `retry` callback, so nearly every real codebase
writes the same "don't retry 4xx" predicate. Kwery ships it:

```kotlin
RetryPolicy.Times(3).exceptWhen { it is HttpException && it.code in 400..499 }
```

`CancellationException` is **never** retried and never counts toward
`failureCount`, regardless of policy. This is enforced in the engine, not left
to user predicates — getting it wrong turns every screen exit into a retry
storm.

### Jitter

Exponential backoff without jitter synchronises retries across clients into a
thundering herd, which matters more on mobile (thousands of devices resuming
from a network blip simultaneously) than in a browser tab. Kwery offers
`RetryDelay.ExponentialWithJitter` — full jitter over `[0, computed]` — and
defaults to plain `Exponential` for parity. See OQ-1.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Query default 3 retries | yes | `RetryPolicy.Default` | planned |
| Mutation default 0 retries | yes | `RetryPolicy.ForMutations` | planned |
| `retry: false` | yes | `Never` | planned |
| `retry: n` | yes | `Times(n)` | planned |
| `retry: true` (infinite) | yes | `Forever` | planned |
| `retry: (count, error) => bool` | yes | `Decide` | planned |
| `failureCount` starts at 0 | yes | yes | planned |
| Exponential backoff 1 s → 30 s cap | yes | `RetryDelay.Exponential` | planned |
| Constant `retryDelay` | yes | `RetryDelay { _, _ -> 2.seconds }` | planned |
| `failureReason` during retries | yes | yes | planned |
| Retries pause when unfocused | yes | see [07](07-refetch-triggers.md) | planned |
| Retries pause when offline | yes | see [13](13-network-mode.md) | planned |
| Cancellation never retried | implicit | **enforced in engine** | divergent (better) |
| Built-in non-retryable predicate | no | `exceptWhen { … }` | divergent (addition) |
| Jitter | no | `ExponentialWithJitter` (opt-in) | divergent (addition) |

## Deliberate divergences

1. **Cancellation handling is engine-level.** Not delegated to user predicates.
2. **`exceptWhen` sugar.** Removes boilerplate every consumer would otherwise
   write, without changing defaults.
3. **Jitter available.** Off by default to preserve parity; documented as
   recommended for production.

## Open questions

- **OQ-1.** Should jitter be the *default*? It is better behaviour for mobile
  fleets, but it breaks exact parity and makes retry timing non-deterministic in
  consumers' tests unless the `TimeSource`-driven harness also controls the
  jitter source. Leaning: keep `Exponential` as default, recommend jitter in
  docs, make the randomness source injectable so tests stay deterministic.
- **OQ-2.** Should `Retry-After` headers be honoured automatically? It requires
  the core to know something about HTTP, which it deliberately does not. Better
  as a documented `RetryDelay` recipe than a built-in.

## Definition of done

- [ ] `RetryPolicy`, `RetryDelay`, `exceptWhen` implemented.
- [ ] Test: exactly 3 retries by default, then `status = Error`.
- [ ] Test: delays are 1 s, 2 s, 4 s… capped at 30 s, verified on virtual clock.
- [ ] Test: `failureCount` increments and `failureReason` is populated while
      `error` stays null until the final attempt.
- [ ] Test: `CancellationException` neither retries nor increments
      `failureCount` — under `Forever`, which would otherwise loop forever.
- [ ] Test: mutations default to no retries.
- [ ] Test: jitter stays within `[0, computed]` with an injected random source.
