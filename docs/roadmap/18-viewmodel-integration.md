# 18 — ViewModel Integration

| | |
|---|---|
| **Tier** | 3 — v1 integration |
| **Status** | planned |
| **Module** | `kwery-core` (+ docs), optional `kwery-lifecycle` |
| **TanStack source** | none — Kwery-specific |
| **Depends on** | 05 Observers |

**This feature has no TanStack counterpart and is the main reason Kwery exists.**
Soil is Compose-only; a large share of production Android apps put server state
in a ViewModel. If this surface is not excellent, Kwery has no wedge.

## The target

```kotlin
class TodoViewModel(
    private val client: QueryClient,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val filter = savedState.getStateFlow("filter", TodoFilter.All)

    val todos: StateFlow<QueryState<List<Todo>>> =
        filter
            .flatMapLatest { client.query(TodoListKey(it)) { api.todos(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueryState())

    fun refresh() = viewModelScope.launch { client.invalidateQueries(TodoListKey(filter.value)) }
}
```

Nothing here is Kwery-specific ceremony — it is the standard Android pattern,
and that is the point. The library's job is to make sure this composes correctly
rather than to introduce a parallel idiom.

## The problem to solve

`SharingStarted.WhileSubscribed(5_000)` and Kwery's own observer grace period
([05](05-deduplication-observers.md)) are two independent timeouts stacked on top
of each other. Left alone, the effective behaviour is the product of both, which
nobody can reason about:

- Rotation: `WhileSubscribed` keeps the upstream alive for 5 s, so Kwery may
  never even see a detach — fine.
- Backstack navigation: the ViewModel survives, `WhileSubscribed` elapses, Kwery
  sees a detach and starts its own grace period, then `gcTime`. Three timers in
  sequence before eviction.
- `SharingStarted.Lazily`: Kwery never sees a detach at all, so `gcTime` never
  starts and the entry leaks for the ViewModel's lifetime.

**Resolved by the [05](05-deduplication-observers.md) spike.** Measured results:

- **Rotation under `WhileSubscribed`: zero extra requests.** The upstream stays
  alive, so the cache never sees a detach. The ViewModel pattern was already
  safe — the rotation bug lives in *direct* collection (`rememberQuery`), and is
  fixed there by grace-aware reattach suppression.
- **`Lazily` leaks, confirmed:** entry still cached 10 minutes after the last
  collector stopped, never evicted. Deliverable 3 below stands.
- **Stacking is negligible:** 310 s to eviction with `WhileSubscribed` versus
  305 s without. No custom `SharingStarted` needed.

## Planned deliverables

1. **A documented, tested recipe** for the canonical ViewModel pattern above,
   stating exactly what happens on rotation, backstack, process death, and
   configuration change — with observed request counts, not prose reassurance.
2. ~~**`SharingStarted.WhileQueryObserved`**~~ — **dropped.** The
   [05](05-deduplication-observers.md) spike measured the stacking directly:
   `WhileSubscribed(5s)` + 5 s grace + 5 min `gcTime` evicts 310 000 ms after
   the screen closes, against 305 000 ms without the grace layer. A 1.6 %
   difference does not justify a third concept. Plain `WhileSubscribed` is fine.
3. **A guard against `Lazily`.** Detect a query observed by a never-completing
   collector and log a warning naming the key, rather than leaking silently.
4. **`SavedStateHandle` interaction guidance.** Query *keys* belong in saved
   state; query *data* does not — that is what [15](15-persistence.md) is for.
   Putting response data in `SavedStateHandle` risks `TransactionTooLargeException`,
   which is a crash users will hit if the docs stay silent.

## Parity table

Not applicable — no TanStack equivalent. Tracking the deliverables instead:

| Deliverable | Status |
|---|---|
| Canonical ViewModel recipe, tested | planned |
| ~~`WhileQueryObserved` sharing strategy~~ | dropped — see above |
| `Lazily` leak warning | planned |
| `SavedStateHandle` guidance | planned |
| Sample app screen using ViewModel (no Compose state holder) | planned |
| Interop with `flatMapLatest` over changing keys | planned |

## Open questions

- **OQ-1.** ~~Is `WhileQueryObserved` worth shipping?~~ **Closed: no.** The spike
  measured the stacking cost at 5 s on a 5-minute `gcTime`. Documentation is
  enough.
- **OQ-2.** Should `kwery-lifecycle` exist as its own artifact, or do these
  helpers belong in `kwery-android`? Leaning: fold into `kwery-android`; another
  artifact for two utilities is not worth the release surface.
- **OQ-3.** Should Kwery offer a `QueryState` → UI-model mapping helper for the
  common case of combining several queries into one screen state? `combine` over
  several `QueryState`s with correct aggregate loading/error semantics is
  fiddly and everyone writes it. Possibly `combineQueryStates(vararg)`.

## Definition of done

- [ ] Recipe documented with a real, compiling sample in the sample app.
- [ ] Test: rotation causes zero refetches and zero evictions.
- [ ] Test: backstack navigation beyond the grace window evicts exactly once
      after `gcTime`, with total request count asserted.
- [ ] Test: `flatMapLatest` over a changing key cancels the old observer and
      subscribes the new one, with no interleaved duplicate requests.
- [ ] Test: `SharingStarted.Lazily` produces the documented warning.
- [ ] OQ-1 resolved after the [05](05-deduplication-observers.md) spike.
