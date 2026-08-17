# 05 — Deduplication & Observers

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned — **needs a spike before design is settled** |
| **Module** | `kwery-core` |
| **TanStack source** | [`reference/QueryObserver.md`](../../.reference/tanstack-query/docs/reference/QueryObserver.md), [`guides/render-optimizations.md`](../../.reference/tanstack-query/docs/framework/react/guides/render-optimizations.md) |
| **Blocks** | 04 Caching lifecycle |
| **Decision** | AD-2 (Flow-first surface) |
| **Risk** | **High — the hardest problem in the library** |

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

**Leaning toward C**, because it matches what Android developers already expect
and keeps the ergonomic `Flow` surface, with A's mechanism underneath. But the
grace window is a second knob interacting with `gcTime`, and the combination
needs to be *measured*, not reasoned about — hence the spike.

## Required spike

Before this design is finalised, build a throwaway harness that:

1. Implements approach C behind the intended API.
2. Drives it through: rotation, background/foreground, backstack navigation,
   process death, and two screens observing the same key simultaneously.
3. Asserts observed request counts and eviction timing against expectations.

**Output is an answer, not code to keep.** Specifically: does the grace period
make `gcTime` behaviour predictable, and what is a defensible default? If C
proves unpredictable, fall back to B and hide the handles in the adapters.

Do not start `kwery-core`'s cache implementation until this resolves — every
other feature depends on the eviction semantics being settled.

## Sketch (pending spike)

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
| Observer count drives gc | mount/unmount | **approach TBD** | **blocked on spike** |
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

- **OQ-1.** Blocking: which approach (A / B / C), and what grace default?
- **OQ-2.** Should the grace period be configurable per query, or client-wide
  only? Per-query is more flexible and more rope.
- **OQ-3.** Does an observer that has been collected but is suspended in
  backpressure count as active? Almost certainly yes, but it needs a test.

## Definition of done

- [ ] Spike completed and its recommendation recorded here, with OQ-1 closed.
- [ ] Test: two concurrent observers of one key produce **exactly one** request.
- [ ] Test: an observer attaching mid-flight joins rather than restarting.
- [ ] Test: cancelling one of two observers does not cancel the shared request.
- [ ] Test: cancelling the **last** observer cancels the in-flight request.
- [ ] Test: rotation (detach + reattach within the grace window) causes **no**
      refetch and **no** eviction.
- [ ] Test: `select` + `distinctUntilChanged` suppresses unchanged emissions.
- [ ] Stress test: 1000 observers across 100 keys, asserting no entry leaks
      after all scopes cancel.
