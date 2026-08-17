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

- **OQ-1.** ~~Should `gcTime` be enforced as ≥ `staleTime`?~~ **Closed: no check
  at all — not even a warning.** The earlier leaning (warn) was wrong.
  `StaleTime.Infinite` with a normal `gcTime` is the idiomatic "cache until I
  invalidate" configuration, and it makes `gcTime < staleTime` **permanently
  true**. A warning would fire constantly on correct code, which trains users to
  ignore warnings. The genuinely dangerous case is `gcTime` vs persistence
  `maxAge` ([15](15-persistence.md)), which still throws, because there the
  failure is silent and defeats the feature.

- **OQ-2.** ~~Memory-pressure eviction via `onTrimMemory`?~~ **Closed, and the
  question was too narrow.** `gcTime` bounds the cache by *time* and nothing
  bounds it by *size*. A browser tab gets reloaded; an Android process can live
  for days, so an unbounded cache of large responses is a real OOM risk that
  TanStack never had to solve. Two decisions:

  1. **`kwery-core` gets `maxEntries`, default 500**, with LRU eviction. Only
     **inactive** entries are ever evicted this way — an entry with a live
     observer is never dropped, whatever the pressure, because evicting data
     that is on screen is worse than using the memory.
  2. **`kwery-android` gets opt-in `onTrimMemory` integration**, dropping
     inactive entries at `TRIM_MEMORY_RUNNING_CRITICAL`.

  500 is far above any real screen graph's working set and far below anything
  that threatens a heap. It bounds worst-case memory deterministically instead of
  hoping `gcTime` is enough.

## Definition of done

- [ ] `StaleTime`, `QueryOptions`, `TimeSource` implemented.
- [ ] The five-step lifecycle from `guides/caching.md` reproduced as a single
      integration test against a virtual clock.
- [ ] Test: `Infinite` yields to `invalidateQueries`; `Static` does not.
- [ ] Test: `Static` blocks `refetchOnMount = "always"`.
- [ ] Test: gc timer cancelled and restarted correctly across detach/reattach.
- [ ] Test: `gcTime = Duration.INFINITE` never evicts and does not overflow.
- [ ] Whole suite runs with zero real `delay()` calls.
