# 05 — Deduplication & Observers

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | spec settled — **spike complete, OQ-1 closed** |
| **Module** | `kwery-core` |
| **TanStack source** | [`reference/QueryObserver.md`](../../.reference/tanstack-query/docs/reference/QueryObserver.md), [`guides/render-optimizations.md`](../../.reference/tanstack-query/docs/framework/react/guides/render-optimizations.md) |
| **Blocks** | 04 Caching lifecycle |
| **Decision** | AD-2 (Flow-first surface) |
| **Risk** | Medium — was the hardest open problem; resolved by the spike |

Two observers of the same key must share one cache entry and one in-flight
request, and the entry must be evicted once the last observer goes away. On the
web the second half is trivial. On a `Flow`-first surface it is not, and this
document says so rather than pretending otherwise.

## TanStack behaviour

`QueryObserver` is the unit of subscription. Multiple observers attach to one
`Query`. The `Query` holds the data, the retry state, and the single in-flight
promise; observers receive updates and may narrow what they select.

Deduplication falls out of this: an observer attaching while a fetch is in
flight joins the existing promise rather than starting a second request.

Eviction falls out of React's lifecycle: `useQuery` subscribes on mount and
unsubscribes on unmount, both precisely defined moments. When the observer count
reaches zero the entry is inactive and the `gcTime` timer starts.

## Why this is harder in Kotlin

React gives an unambiguous "this consumer is gone" signal. Flow collection does
not:

- A `Flow` collected in `viewModelScope` ends when the ViewModel clears —
  correct, but coarse.
- `stateIn(scope, SharingStarted.WhileSubscribed(5_000), …)` — the idiomatic
  Android pattern — introduces a *second* independent timeout that already
  handles rotation. Naively treating "upstream flow cancelled" as "last observer
  left" means a screen rotation either restarts `gcTime` spuriously or, worse,
  the two timeouts compound into cache behaviour nobody can predict.
- `SharingStarted.Lazily` never unsubscribes at all, so `gcTime` would never
  start and the cache would grow without bound.

Getting this wrong fails in one of two directions, both bad: entries leak
forever (memory growth, stale data resurrected), or entries evict while still on
screen (spurious refetches, flicker, wasted requests).

## Candidate approaches

**A — Ref-count on Flow collection.** The observer registers on collection start
and deregisters in `onCompletion`. Simple and automatic. Risk: the
`WhileSubscribed` interaction above; every rotation is a detach/reattach cycle.

**B — Explicit observer handles.** `client.observe(key)` returns a
`QueryObserver` that must be `close()`d; the `Flow` is a property of it.
Unambiguous lifetime, testable, but pushes lifecycle management onto users and
is un-Kotlin-ish. Compose and ViewModel adapters would hide it.

**C — Ref-count with a grace period.** Like A, but the transition to *inactive*
is deferred by a short configurable window (default ~5 s) that absorbs
rotation and navigation churn. Effectively `WhileSubscribed` semantics inside
the library, so consumers do not stack two timeouts.

## Spike findings

A throwaway harness (JVM, `kotlinx-coroutines-test`, virtual clock) implemented
approach C and measured request counts and eviction timing across 13 scenarios.
Results below are observed, not predicted.

**The design assumption was wrong.** The grace period does *not* prevent
rotation refetches:

| # | Scenario | Extra requests |
|---|---|--:|
| S3 | rotation, direct collection, `staleTime=0`, grace 5 s | **1** |
| S4 | same but `staleTime=30s` | 0 |
| S7 | rotation under `stateIn(WhileSubscribed(5s))`, `staleTime=0` | 0 |

Reattaching 50 ms after detaching — comfortably inside a 5-second grace window —
still fired a second request. The reason is that refetch-on-mount is driven by
**staleness**, not by observer accounting: the new observer attaches, finds the
data stale (`staleTime=0` makes it stale instantly), and refetches. The grace
period only governs *eviction*, so it never enters the decision.

S7 explains why this is easy to miss: under the canonical ViewModel pattern,
`WhileSubscribed` keeps the upstream alive across rotation, so the cache never
sees a detach and the bug is invisible. **The problem appears precisely where
the design expected it least — direct collection, which is what `rememberQuery`
does.**

### What the grace period is actually for

It earns its place, just not for the stated reason:

| # | Scenario | Result |
|---|---|---|
| S9 | leave and return inside grace with a slow request in flight | **1** request — the in-flight was joined, not restarted |
| S10 | abandon a slow request, never return | in-flight cancelled at exactly grace expiry |

Without grace, navigating away mid-request cancels it, and returning a moment
later starts over — wasting the request and making the user wait twice.

### Eviction timing

| # | Scenario | Delay after leaving |
|---|---|--:|
| S5 | direct collection, grace 5 s + `gcTime` 5 min | 305 000 ms |
| S6 | `WhileSubscribed(5s)` on top | 310 000 ms |

The three timeouts **do** stack, exactly as feared — but the total is 5 s on top
of a 5-minute `gcTime`, a 1.6 % difference. **Stacking is real and irrelevant.**
It does not justify a bespoke `SharingStarted`.

| # | Scenario | Result |
|---|---|---|
| S8 | `SharingStarted.Lazily` | never evicted; still cached after 10 min |

The leak is confirmed and needs the diagnostic warning described in
[18](18-viewmodel-integration.md).

### The fix: grace-aware reattach (option C′)

Rather than change the `staleTime` default — a visible parity divergence on the
library's most prominent option — treat a reattach landing **inside the grace
window** as a continuation rather than a fresh mount, so it skips the
refetch-on-mount staleness check. Verified:

| # | Scenario | Extra requests | Correct? |
|---|---|--:|---|
| S11 | rotation, `staleTime=0`, suppression on | **0** | yes |
| S12 | return after 30 s (past grace) | 1 | yes — stale data must refresh |
| S13 | return 100 ms after grace expired | 1 | yes — boundary is sharp |

C′ removes the rotation refetch without suppressing any refetch that was
genuinely needed. The grace window becomes one knob doing two coherent jobs:
it defers eviction, and it defines what counts as "the same mount".

## Decision (OQ-1 closed)

**Approach C′: ref-counting on Flow collection, a grace window before an entry
goes inactive, and reattachment inside that window treated as a continuation.**

- Default grace period: **5 seconds**, matching the `WhileSubscribed` value
  Android developers already use.
- Keep `staleTime` defaulting to `0` — parity preserved.
- Do **not** ship a custom `SharingStarted`; stacking is negligible
  ([18](18-viewmodel-integration.md) OQ-1 resolved: drop `WhileQueryObserved`).
- Approach B (explicit observer handles) is not needed.

Every number above is reproducible as a test; they are folded into the
definition of done below.

## Sketch

```kotlin
internal class QueryEntry<T>(
    val key: QueryKey<T>,
    private val clock: TimeSource,
) {
    private val observers = MutableStateFlow(0)
    private var inFlight: Deferred<T>? = null      // dedup: joined, not restarted
    private var gcJob: Job? = null

    /** Joins an in-flight fetch if one exists, otherwise starts one. */
    suspend fun fetch(fn: QueryFn<T>): T = /* … */
}
```

Deduplication itself is the easy half: memoising a `Deferred<T>` for the
in-flight request gives it directly, and `CoroutineScope.async` handles the
cancellation semantics. It is only the eviction side that is uncertain.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Multiple observers share one entry | yes | yes | planned |
| Concurrent fetches deduplicated | yes | memoised `Deferred` | planned |
| Observers share `status`/`fetchStatus` | yes | yes | planned |
| Observer count drives gc | mount/unmount | ref-count + grace (C′) | planned |
| Reattach inside grace is not a mount | n/a | yes — kills rotation refetch | divergent (better) |
| `select` to narrow observed data | yes | see below | planned |
| Re-render only on selected change | `select` + structural sharing | `Flow.distinctUntilChanged` | planned |
| `notifyOnChangeProps` | yes | not needed — `Flow` is already pull-based | divergent |

### `select`

```kotlin
client.query(TodoListKey(f)).select { todos -> todos.count { !it.done } }
```

`select` composes as a `Flow` operator plus `distinctUntilChanged`, so an
observer selecting a count only emits when the count changes. This is
`render-optimizations.md`'s goal reached with standard operators rather than a
bespoke mechanism.

## Deliberate divergences

1. **No `notifyOnChangeProps`.** It exists because React re-renders on any state
   object change. `Flow` consumers already choose what to collect.
2. **No structural sharing.** See the roadmap non-goals: `data class` equality
   plus `distinctUntilChanged` covers the need.

## Open questions

- **OQ-1.** ~~Which approach, and what grace default?~~ **Closed by the spike:
  approach C′, 5-second default.** See Decision above.
- **OQ-2.** ~~Per-query grace, or client-wide?~~ **Closed: client-wide only.**
  Since C′ makes the grace window define what counts as "the same mount",
  a per-query grace would mean rotation behaves differently on different screens
  of the same app — an inconsistency users would experience as flakiness and
  never be able to explain. One knob, one behaviour.

- **OQ-3.** ~~Does a backpressure-suspended observer count as active?~~
  **Closed: yes.** It is still collecting and `onCompletion` has not fired;
  anything else would evict data belonging to a slow consumer. This is the
  implementation doing the obvious thing, but it gets an explicit test because
  it is the kind of invariant that breaks silently under refactoring.

- **OQ-4.** *(raised by the spike)* ~~Should suppression apply to
  `refetchOnWindowFocus` too?~~ **Closed: yes** — and this resolves
  [07](07-refetch-triggers.md) OQ-1 as well.

  A brief app switch (notification shade, replying to a message, checking the
  app switcher) produces `ON_STOP`/`ON_START` within a couple of seconds. With
  `collectAsStateWithLifecycle` that is also a detach/reattach. Without
  suppression, every such switch refetches every visible query — the refetch
  storm flagged in 07, on cellular, repeatedly.

  Applying the same grace window to focus-triggered refetches fixes it with the
  concept already in the design, rather than adding a bespoke focus throttle
  with its own separate tuning knob. Returning **after** the grace window still
  refetches normally, which is the behaviour users actually want from
  foregrounding.

### Multi-observer coverage was the blind spot

Nearly every test in the suite used a **single** observer, and a whole class of
bug lives in the difference: work that should happen once per *entry* but
accidentally happens once per *observer*, and teardown that should wait for the
last observer but runs on the first to leave.

Two guards turned out to be entirely untested, both found by mutating them and
watching nothing fail:

1. **`if (pollJob != null) return`** in `startPollingLocked`. Without it, every
   observer starts its own polling loop.
2. **`if (observers > 0) return@withLock`** in `detach`. Without it, one screen
   closing stops polling and schedules eviction while another screen is still
   watching.

The first one is the interesting case. **Request counts cannot see it.** Two
polling loops tick at the same instants, so their fetches are deduplicated into
a single request — the count looks perfect while an extra coroutine wakes up for
ever. Counting *interval evaluations* makes it visible: 13 for one loop, 26 for
two. That is the same technique the polling-stop leak needed in
[07](07-refetch-triggers.md), and the second time the same blind spot has hidden
the same shape of defect.

Note that 13, not 6, is the correct figure for one loop over 60s at a 10s
interval: the interval is deliberately evaluated twice per tick, once before the
delay and once after, so that turning polling off takes effect immediately
rather than one tick late.

### The guard sweep: four resolved, one kept unverified

Mutating each guard in `QueryEntry` and `InfiniteQuery` and watching what the
suite noticed produced six survivors. Resolved as follows.

**Covered by new tests** — `focus-needs-observer`, `windowed-trim`, the trim's
eviction *direction*, and the `!force` staleness check (that last one exposed a
real bug, recorded in [20](20-prefetching.md)).

**Removed as redundant** — `if (state.value.isInvalidated) return null` in
`invalidate`. Both properties its comment claimed are structural: an equal state
copy publishes nothing through `StateFlow`, and `startFetchLocked` joins the
in-flight request rather than starting a second. A guard that restates a
guarantee the surrounding code already gives reads as though it is load-bearing.

**Removed because it was wrong** — `withinContinuationWindow()` in
`onEnvironmentTrigger`. Brief-switch suppression is implemented in
`QueryClient.observeReturns`, which does not notify entries at all unless the
app was away longer than the grace period; the check here was redundant in the
common case. It was also actively harmful in one: an Activity recreated *while*
the app was away stamps its continuation at the moment of return, which
suppressed a refetch the user genuinely wanted — and whether it did depended on
whether `attach` or the focus event happened to land first. There is now a test
for that case, and re-adding the guard fails it.

That also made `lastContinuationMillis` write-only, so it is gone too. The
continuation concept still exists where it belongs: in `attach`, deciding
whether a reattach counts as a mount.

**Kept, unverified** — the already-armed check in `scheduleEvictionLocked`.
Without it a repeated prefetch launches a waiting coroutine per call. The strays
are harmless (the first timer evicts on time; the rest find the entry gone),
which is exactly why nothing can observe them: a coroutine-counting test passed
with and without the guard, because `runTest` advances virtual time while the
fetch suspends and lets the strays complete. Rather than keep a test that cannot
fail, the guard is documented as a deliberate defensive choice and the failed
attempt recorded here.

### A second observer on a stale query *does* refetch

Worth stating because it looks like a deduplication failure and is not.
`refetchOnMount` applies per mount, so a second screen opening on a query whose
`staleTime` has elapsed issues a request. That is TanStack's behaviour and the
right one — the second screen has no way to know the data on screen is fresh.
The tests above set a `staleTime` specifically to keep mount refetches out of
counts that are about polling.

## Definition of done

Scenario IDs refer to the spike harness; each becomes a real test.

- [x] (S1) Two concurrent observers of one key produce **exactly one** request.
- [x] (S2) An observer attaching mid-flight joins rather than restarting.
- [x] (S11) Rotation with `staleTime = 0` causes **zero** extra requests.
- [x] (S12) Returning after 30 s — past grace — **does** refetch.
- [x] (S13) Returning 100 ms after grace expiry **does** refetch.
- [x] (S9) Leaving and returning inside grace joins the existing request.
- [x] (S10) Abandoning past grace cancels the in-flight request at grace expiry.
- [x] (S5) Eviction occurs at `grace + gcTime` after the last observer leaves.
- [x] Test: two, three and four observers of a polling query share one loop.
      **Verified by mutation**, via interval evaluations rather than requests.
- [x] Test: one observer leaving stops neither polling nor delays eviction for
      the others; the last one leaving does both. **Verified by mutation.**
- [x] Test: `observerCount` tracks arrivals and departures.
- [ ] ~~(S8) `SharingStarted.Lazily` detection and warning.~~ **Dropped**, for
      the reason recorded in [18](18-viewmodel-integration.md): the cache cannot
      distinguish a leaked collector from a screen left open all afternoon, so
      any heuristic fires on correct code. `QueryEntrySnapshot.observedSinceMillis`
      exposes the data instead, and is tested there.
- [x] Test: cancelling one of two observers does not cancel the shared request.
- [x] Test: `select` suppresses emissions when the projection is unchanged.
- [x] Stress test: 500 observers across 100 keys — one entry and one request
      per key, and every entry evicted once its observers are gone.

> Note for whoever writes these: `advanceUntilIdle()` does **not** dispatch
> coroutines launched in `backgroundScope`. The spike initially measured zero
> requests everywhere because of it. Use explicit `runCurrent()` /
> `advanceTimeBy()`.
