# 04 — Caching Lifecycle

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/caching.md`](../../.reference/tanstack-query/docs/framework/react/guides/caching.md), [`guides/important-defaults.md`](../../.reference/tanstack-query/docs/framework/react/guides/important-defaults.md) |
| **Blocks** | 05 Observers, 15 Persistence |

`staleTime` and `gcTime` are two different clocks answering two different
questions, and conflating them is the most common misunderstanding of TanStack
Query.

- **`staleTime`** — how long data is considered *fresh*. Governs **refetching**.
- **`gcTime`** — how long an *unobserved* entry stays in memory. Governs
  **eviction**.

## TanStack behaviour

Defaults, verbatim from the docs:

| Option | Default | Meaning |
|---|---|---|
| `staleTime` | `0` | data is stale immediately |
| `gcTime` | `1000 * 60 * 5` (5 min) | inactive entries evicted after 5 min |
| `retry` | `3` | with exponential backoff |

`staleTime` accepts three kinds of value, and the distinction between the last
two is subtle but real:

- a duration — fresh until it elapses
- `Infinity` — never stale from time, **but `invalidateQueries` still works**
- `'static'` — never refetches at all; invalidation has **no effect**, and
  `refetchOnMount`/`refetchOnWindowFocus`/`refetchOnReconnect` set to `"always"`
  are blocked. Intended for data that cannot change while the app runs: feature
  flags fetched at boot, permissions loaded at login, static reference tables.

Stale entries refetch in the background when a new observer mounts, the window
refocuses, or the network reconnects.

The lifecycle from `guides/caching.md`:

1. First observer for a key → hard loading state, network request, cache under key.
2. Second observer for the same key → **cached data returned immediately**, and
   a new request fires. Both observers share status, because they share the key.
3. All observers gone → entry becomes *inactive*, a `gcTime` timer starts.
4. A new observer before the timer fires → cached data served immediately,
   background refetch, timer cancelled.
5. Timer fires with no observers → entry deleted.

## Kwery design

```kotlin
@JvmInline
value class StaleTime private constructor(private val raw: Long) {
    companion object {
        fun of(duration: Duration) = StaleTime(duration.inWholeMilliseconds)
        /** Never stale by time; still yields to invalidation. */
        val Infinite = StaleTime(-1)
        /** Never refetches, and ignores invalidation entirely. */
        val Static = StaleTime(-2)
    }
}

data class QueryOptions<T>(
    val staleTime: StaleTime = StaleTime.of(Duration.ZERO),
    val gcTime: Duration = 5.minutes,
    val retry: RetryPolicy = RetryPolicy.Default,
    val enabled: Boolean = true,
    // …
)
```

Modelling `staleTime` as a value class rather than a `Duration` is what makes
`Infinite` and `Static` expressible without sentinel magic numbers leaking into
user code. `Duration.INFINITE` could stand in for `Infinite`, but there is no
honest `Duration` that means "also ignore invalidation".

### Time is injected

The cache never calls `System.currentTimeMillis()` directly:

```kotlin
fun interface TimeSource { fun nowMillis(): Long }
```

`QueryClient` takes a `TimeSource` defaulting to the system clock; tests inject
a virtual one. Every `staleTime`/`gcTime` test then runs in microseconds with no
`delay()`, which is the difference between a suite that gets run and one that
gets skipped.

### Eviction

`gcTime` timers are coroutine jobs on the client's scope, cancelled when an
observer reattaches. Entry state transitions are:

```
active ──(last observer leaves)──> inactive ──(gcTime elapses)──> evicted
   ^                                    │
   └──────(observer attaches)───────────┘
```

Reference counting is delegated to [05](05-deduplication-observers.md), which is
where the hard part lives.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `staleTime` duration | yes | `StaleTime.of(d)` | planned |
| `staleTime: Infinity` | yes | `StaleTime.Infinite` | planned |
| `staleTime: 'static'` | yes | `StaleTime.Static` | planned |
| `Static` blocks invalidation | yes | yes | planned |
| Default `staleTime` = 0 | yes | yes | planned |
| `gcTime` | yes | yes | planned |
| Default `gcTime` = 5 min | yes | yes | planned |
| Cached data served instantly to new observer | yes | yes | planned |
| gc timer cancelled on reattach | yes | yes | planned |
| Per-query and global defaults | yes | yes | planned |
| Max `gcTime` ~24 days (`setTimeout` limit) | JS limitation | **no limit** — coroutine delay is `Long` | divergent (better) |
| Injectable clock | no (`timeoutManager` in v5) | `TimeSource`, first-class | divergent (better) |

## Deliberate divergences

1. **No 24-day ceiling.** TanStack caps `gcTime` at roughly 24 days because
   `setTimeout` overflows a 32-bit delay. `delay()` takes a `Long`, so
   `gcTime = Duration.INFINITE` is expressible and honest.
2. **`TimeSource` is public API.** TanStack added `timeoutManager` late; Kwery
   treats an injectable clock as a first-class testing affordance from day one
   and documents it for consumers testing their own repositories.
3. **`StaleTime` value class.** Prevents the `Infinity`/`'static'` distinction
   from being a stringly-typed special case.

## Open questions

- **OQ-1.** Should `gcTime` be enforced as ≥ `staleTime`? A `gcTime` shorter
  than `staleTime` means data is evicted while still nominally fresh, which is
  almost always a mistake — but it is legal in TanStack and might have niche
  uses. Leaning: warn once via a logger, do not throw. (Contrast with the
  `gcTime` vs persistence `maxAge` check in [15](15-persistence.md), which
  **does** throw, because there the failure is silent and unrecoverable.)
- **OQ-2.** Should eviction be purely timer-driven, or should Kwery also support
  a memory-pressure trigger via `ComponentCallbacks2.onTrimMemory`? Android-only
  and genuinely useful for image-heavy caches, but it makes eviction
  non-deterministic. Candidate for `kwery-android` as opt-in, post-v1.

## Definition of done

- [ ] `StaleTime`, `QueryOptions`, `TimeSource` implemented.
- [ ] The five-step lifecycle from `guides/caching.md` reproduced as a single
      integration test against a virtual clock.
- [ ] Test: `Infinite` yields to `invalidateQueries`; `Static` does not.
- [ ] Test: `Static` blocks `refetchOnMount = "always"`.
- [ ] Test: gc timer cancelled and restarted correctly across detach/reattach.
- [ ] Test: `gcTime = Duration.INFINITE` never evicts and does not overflow.
- [ ] Whole suite runs with zero real `delay()` calls.
