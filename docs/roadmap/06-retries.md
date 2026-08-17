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

### Jitter (on by default)

Exponential backoff without jitter synchronises retries across clients into a
thundering herd — thousands of devices resuming from one carrier-level blip and
retrying in lockstep. Kwery therefore **defaults to jitter**, unlike TanStack
(see OQ-1 for the full reasoning):

```kotlin
/** base/2 + random(0, base/2) — decorrelates the fleet, keeps a floor. */
val Default: RetryDelay = RetryDelay.equalJitter(Exponential)
```

`RetryDelay.Exponential` remains available for exact TanStack timing. The
`Random` source is injectable so consumer tests stay deterministic.

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
| Jitter | no | **on by default** (equal jitter) | divergent (better) |
| Deterministic retry timing in tests | no | injectable `Random` | divergent (better) |

## Deliberate divergences

1. **Cancellation handling is engine-level.** Not delegated to user predicates.
2. **`exceptWhen` sugar.** Removes boilerplate every consumer would otherwise
   write, without changing defaults.
3. **Jitter available.** Off by default to preserve parity; documented as
   recommended for production.

## Open questions

- **OQ-1.** ~~Should jitter be the default?~~ **Closed: yes, jitter is ON by
  default.** The earlier leaning (off, for parity) was wrong — it optimised for
  matching TanStack over being correct.

  Un-jittered exponential backoff synchronises a fleet: a regional network blip
  drops thousands of devices simultaneously, and they all retry at t+1s, t+2s,
  t+4s together, converting one outage into a self-inflicted thundering herd on
  recovery. Browsers are partly shielded by users being spread across time and
  tabs; a mobile fleet coming back from a carrier-level event is not. Nobody
  should have to opt in to not doing this.

  The default is **equal jitter** — `delay = base/2 + random(0, base/2)` —
  rather than full jitter (`random(0, base)`). Full jitter can produce a
  near-zero delay that retries almost immediately, defeating the backoff on the
  first attempt; equal jitter keeps a guaranteed floor while still
  decorrelating the fleet.

  The divergence is invisible in the API and affects only timing. The `Random`
  source is injectable, so consumer tests stay deterministic.

- **OQ-2.** ~~Honour `Retry-After` automatically?~~ **Closed: no, and it stays
  out of core permanently.** `kwery-core` knows nothing about HTTP by design
  (AD-1), and adding header parsing would put a protocol into a
  protocol-agnostic module. Ship it as a documented `RetryDelay` recipe instead —
  it is about six lines in user code and stays correct across HTTP clients.

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
