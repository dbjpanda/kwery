# 04 — Caching Lifecycle

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | **gate 2 complete** |
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
sealed interface StaleTime {
    data class After(val duration: Duration) : StaleTime
    /** Never stale by time; still yields to invalidation. */
    data object Infinite : StaleTime
    /** Never refetches, and ignores invalidation entirely. */
    data object Static : StaleTime

    companion object {
        val Zero: StaleTime = After(Duration.ZERO)
        fun of(duration: Duration): StaleTime = After(duration)
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

Modelling `staleTime` as its own type rather than a `Duration` is what makes
`Infinite` and `Static` expressible without sentinel magic numbers leaking into
user code. `Duration.INFINITE` could stand in for `Infinite`, but there is no
honest `Duration` that means "also ignore invalidation".

**Correction from implementation:** this originally specified a `@JvmInline
value class` wrapping a `Long` with `-1`/`-2` sentinels. A sealed interface is
better and the value class's justification did not survive contact: the
allocation it avoided happens once per `QueryOptions`, not per cache lookup, so
there was nothing on a hot path to optimise. The sealed form gives an exhaustive
`when`, no sentinel encoding to get wrong, and readable `toString()` in test
failures.

### Wall-clock robustness

Staleness compares epoch millis, and wall-clock time can move **backwards** — an
NTP correction, or the user changing the device clock. That puts `dataUpdatedAt`
in the future, and a naive `now - dataUpdatedAt >= staleTime` then reports the
data fresh until the clock catches up, potentially for hours, with no way to
recover.

A negative elapsed time is therefore treated as **elapsed**. Refetching earlier
than necessary is a cost; serving stale data indefinitely is a bug. TanStack has
the same exposure and no such guard.

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
| Injectable clock | no (`timeoutManager` in v5) | `TimeSource`, first-class | done |
| Backwards clock jump handled | no | treated as stale | divergent (better) |

## Deliberate divergences

1. **No 24-day ceiling.** TanStack caps `gcTime` at roughly 24 days because
   `setTimeout` overflows a 32-bit delay. `delay()` takes a `Long`, so
   `gcTime = Duration.INFINITE` is expressible and honest.
2. **`TimeSource` is public API.** TanStack added `timeoutManager` late; Kwery
   treats an injectable clock as a first-class testing affordance from day one
   and documents it for consumers testing their own repositories.
3. **`StaleTime` sealed interface.** Prevents the `Infinity`/`'static'`
   distinction from being a stringly-typed special case, with an exhaustive
   `when` and no sentinel encoding.
4. **Backwards clock jumps are treated as stale.** TanStack has the same
   wall-clock exposure and no guard; without one, an NTP correction can pin data
   as "fresh" for hours with no way to recover.

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

### `maxEntries` was documented and untested

The size bound is in `docs/caching.md`, in `docs/deduplication.md` and in the
`QueryClientConfig` KDoc. Nothing tested it: mutating
`if (entries.size <= config.maxEntries) return` killed nothing, because the loop
below it breaks immediately when the overflow is not positive. The guard was a
pure fast path restating what the loop already does, so it is gone.

The behaviour it fronted, however, is real and now has tests — including the two
parts that are easy to get backwards: eviction takes the **least** recently used
first, and an **observed** entry is never evicted no matter the pressure.
Discarding data that is on screen to save memory is worse than using the memory.

## Definition of done

- [x] Test: the cache does not grow past `maxEntries` under churn.
- [x] Test: eviction takes the least recently used first. **Verified by
      mutation** — reversing the sort order fails it.
- [x] Test: an observed entry is never evicted. **Verified by mutation** —
      dropping the `observerCount == 0` filter fails it.
- [x] `StaleTime` and `TimeSource` implemented, with the backwards-clock guard
      verified by mutation. `QueryOptions` lands with the cache.
- [x] The five-step lifecycle from `guides/caching.md` reproduced as a single
      integration test against a virtual clock — each step is individually
      plausible and it is the ORDER that is easy to get wrong.
- [x] Test: `Infinite` yields to invalidation; `Static` does not (`allowsInvalidation`).
- [x] Test: `Static` blocks automatic refetch (`allowsAutomaticRefetch`).
      End-to-end `refetchOnMount = "always"` coverage lands with the cache.
- [x] Test: the gc timer restarts on each detach rather than accumulating.
- [x] Test: an entry a second screen is still watching is never evicted.
- [x] Test: `gcTime = Duration.INFINITE` survives a year of virtual time and
      does not overflow — TanStack caps at ~24 days because `setTimeout` does.
- [x] Whole suite runs on a virtual clock with zero real `delay()` calls.
