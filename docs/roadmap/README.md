# Kwery Roadmap — TanStack Query Parity

Kwery is a TanStack Query equivalent for Android/Kotlin: async server-state
management with caching, deduplication, stale-while-revalidate, mutations,
offline support, and a persisted cache that survives process death.

This folder tracks **feature parity with TanStack Query v5**, one file per
feature area.

Every parity claim cites TanStack's own documentation or test suite. That
material is **not committed** — fetch it once with
`./scripts/vendor-reference.sh`, which pins the revision. The
`.reference/...` links below only resolve after you have run it.

## Positioning

| | Kwery | Soil | Store5 |
|---|---|---|---|
| Primary surface | `Flow` (Compose adapter on top) | Compose only | `Flow`, per-endpoint `Store` |
| Usable from a ViewModel | Yes | No | Yes |
| Ad-hoc `query(key)` from any call site | Yes | Yes | No — Stores declared up front |
| Persisted cache across process death | Yes — first-class | No | Via `SourceOfTruth` |
| Offline mutation queue | Yes | No | Partial |
| TanStack API familiarity | High | Medium | Low |

The wedge: **works outside Compose, and treats persistence and offline as
core rather than as an extension.**

## Architectural decisions (locked)

- **AD-1 — JVM-pure core.** `kwery-core` has no Android dependencies. Android
  concerns (foreground detection, connectivity) enter through interfaces with
  no-op JVM defaults, implemented in `kwery-android`. Keeps the time-dependent
  cache logic testable with a virtual clock, no Robolectric.
- **AD-2 — Flow-first surface.** The core primitive is
  `QueryObserver` → `Flow<QueryState<T>>`. `rememberQuery` in `kwery-compose`
  is a thin adapter over the same primitive, not a parallel implementation.
- **AD-3 — Hybrid query keys.** `interface QueryKey<T> { val parts: List<Any?> }`.
  Typed data classes give compile-time safety; `parts` preserves TanStack's
  **partial** key matching, filters, and stable serialization. `T` is invariant
  — marking it `out` would let the compiler widen `QueryKey<Todo>` to
  `QueryKey<Any>` during inference and defeat the type safety.
- **AD-4 — Two orthogonal status axes.** `status` (pending/error/success) and
  `fetchStatus` (fetching/paused/idle) are separate, exactly as in TanStack.
  Never collapsed into a single sealed class.

## Module layout

```
kwery-core         pure Kotlin/JVM — cache, observers, retries, mutations       [BUILT]
kwery-android      lifecycle FocusManager, validated-connectivity OnlineManager  [BUILT]
kwery-compose      rememberQuery / rememberMutation / rememberInfiniteQuery      [BUILT]
kwery-persist      persistence contracts + dehydrate/hydrate                    [BUILT]
kwery-persist-room        Room/SQLite-backed persister (larger caches; not built)
kwery-devtools     inspection surface (post-v1)
sample             not published — keeps documentation examples compiling     [BUILT]
kwery-test         virtual-clock test harness for consumers                     [BUILT]
```

## Feature index

Every feature passes **three gates in order**: spec written → tests green →
documentation published. See [`CLAUDE.md`](../../CLAUDE.md) for the rules.

Gate status: ○ not started · ◐ in progress · ● done · — not applicable

**This table is checked against the files, not maintained by hand.** Comparing
every row to the checkboxes in its feature file found six rows claiming gate 2
that had open work behind them — including two features whose only gap was a
test I had listed and never written. A status table that only moves forward is
not tracking anything, so rows move back when the file says they should.

A box left open must say *why*: struck through with a recorded decision if it is
deferred, or under a **Requires a device** heading if it cannot run here. An
open box with no reason is unfinished work, and the row above it says `◐`.

A docs page may be **drafted** while its feature's gate 2 is still open, but
gate 3 is only ● once gate 2 is. Items that can only run on a device are listed
under a "Requires a device" heading in their feature file and do not hold a gate
open, since they can never pass in CI as it stands. Features 14 and 15 have pages written and show
◐ for both: what is documented is tested, but each still has open gate-2 items
(a Room-backed store, device tests) so neither feature is closed.

The "Port tests from" column names the file in
`.reference/tanstack-query/packages/query-core/src/__tests__/` (fetch with
`./scripts/vendor-reference.sh`) whose cases should be ported for gate 2. Cases deliberately not ported must be recorded in
that feature's parity table with a reason.

### Tier 1 — v1 core (irreducible)

| # | Feature | Port tests from | Spec | Tests | Docs |
|---|---|---|:--:|:--:|:--:|
| 01 | [Query keys](01-query-keys.md) | `utils.test.tsx`, `queryCache.test.tsx` | ● | ● | ● |
| 02 | [Query functions](02-query-functions.md) | `query.test.tsx` | ● | ● | ● |
| 03 | [Query state & status axes](03-query-state.md) | `queryObserver.test.tsx` | ● | ● | ● |
| 04 | [Caching lifecycle](04-caching-lifecycle.md) | `query.test.tsx`, `queryCache.test.tsx` | ● | ● | ● |
| 05 | [Deduplication & observers](05-deduplication-observers.md) | `queryObserver.test.tsx`, `queriesObserver.test.tsx` | ● | ● | ● |
| 06 | [Retries & backoff](06-retries.md) | `query.test.tsx` | ● | ● | ● |
| 07 | [Refetch triggers](07-refetch-triggers.md) | `focusManager.test.tsx`, `onlineManager.test.tsx` | ● | ● | ● |
| 08 | [Invalidation & filters](08-invalidation-filters.md) | `queryClient.test.tsx`, `utils.test.tsx` | ● | ● | ● |
| 09 | [Manual cache access](09-manual-cache.md) | `queryClient.test.tsx`, `queryObserver.test.tsx` | ● | ● | ● |
| 10 | [Cancellation](10-cancellation.md) | `query.test.tsx` | ● | ● | ● |

### Tier 2 — v1 headline features

| # | Feature | Port tests from | Spec | Tests | Docs |
|---|---|---|:--:|:--:|:--:|
| 11 | [Mutations](11-mutations.md) | `mutations.test.tsx`, `mutationCache.test.tsx`, `mutationObserver.test.tsx` | ● | ● | ● |
| 12 | [Optimistic updates & rollback](12-optimistic-updates.md) | `mutations.test.tsx` | ● | ● | ● |
| 13 | [Network mode & offline pause](13-network-mode.md) | `onlineManager.test.tsx`, `query.test.tsx` | ● | ● | ● |
| 14 | [Offline mutation queue](14-offline-mutation-queue.md) | `mutations.test.tsx`, `hydration.test.tsx` | ● | ◐ | ◐ |
| 15 | [Persistence & hydration](15-persistence.md) | `hydration.test.tsx` | ● | ◐ | ◐ |
| 16 | [Infinite & paginated queries](16-infinite-queries.md) | `infiniteQueryBehavior.test.tsx`, `infiniteQueryObserver.test.tsx` | ● | ● | ● |

### Tier 3 — v1 integration surfaces

| # | Feature | Port tests from | Spec | Tests | Docs |
|---|---|---|:--:|:--:|:--:|
| 17 | [Compose bindings](17-compose-bindings.md) | — (React-specific) | ● | ● | ● |
| 18 | [ViewModel integration](18-viewmodel-integration.md) | — (Kwery-specific) | ● | ● | ● |
| 19 | [Dependent & parallel queries](19-dependent-parallel.md) | `queriesObserver.test.tsx` | ● | ● | ● |
| 20 | [Prefetching](20-prefetching.md) | `queryClient.test.tsx` | ● | ● | ● |
| 21 | [Testing support](21-testing.md) | — (Kwery-specific) | ● | ● | ● |

### Tier 4 — post-v1

| # | Feature | Port tests from | Spec | Tests | Docs |
|---|---|---|:--:|:--:|:--:|
| 22 | [Devtools](22-devtools.md) | — | ◐ | ○ | ○ |
| 23 | [Cross-process cache sync](23-cross-process-sync.md) | — | ◐ | ○ | ○ |
| 24 | [Streamed queries](24-streamed-queries.md) | `streamedQuery.test.tsx` | ◐ | ○ | ○ |

Tier 4 specs are marked ◐ deliberately: each carries a **v1 obligation** — a
constraint the core must satisfy now so the deferred feature stays buildable
later — and those obligations are in scope for v1 even though the features are
not.

## Deliberate non-goals

Features that exist in TanStack Query and will **not** be ported, with reasons:

- **SSR / hydration-from-server** (`guides/ssr.md`, `guides/advanced-ssr.md`) —
  no server-rendering equivalent on Android.
- **Suspense** (`guides/suspense.md`) — no React Suspense analogue. The
  equivalent ergonomics come from `Flow` + structured concurrency.
- **Scroll restoration** (`guides/scroll-restoration.md`) — belongs to
  `LazyListState` / `RecyclerView`, not to a data layer.
- **Structural sharing** (`guides/important-defaults.md`) — exists to preserve
  referential identity for `useMemo`. Kotlin `data class` equality already
  gives Compose what it needs for skipping.
- **Request waterfall analysis** (`guides/request-waterfalls.md`) — a docs
  concern, not an API. May become a devtools view.

## Improvements over TanStack Query

Places where Kwery deliberately does better, not just match. Each is decided and
justified in the linked feature file — none is a preference.

**Correctness and safety**

1. **Typed query keys** ([01](01-query-keys.md)) — compile-time safety on the
   data type a key produces, so `setQueryData` and `select` cannot be handed the
   wrong type. Also deletes TanStack's most common user bug: a query function
   reading a variable missing from the key cannot compile.
2. **Validate `gcTime` ≥ persistence `maxAge` at construction**
   ([15](15-persistence.md)) — TanStack documents this and lets you violate it
   silently, quietly defeating the persisted cache. Kwery throws.
3. **`invalidateQueries` cannot nuke the cache by accident**
   ([08](08-invalidation-filters.md)) — the no-argument form is rejected;
   invalidating everything requires `QueryFilters.All`.
4. **No global default query function** ([02](02-query-functions.md)) — it
   cannot be made type-safe, so it is dropped rather than shipped with an
   unchecked cast. A deliberate parity gap.
5. **Swallowed `CancellationException` is contained** ([10](10-cancellation.md)) —
   a query function with a broad `catch (e: Exception)` still cancels correctly,
   because the engine re-checks `isActive` rather than trusting user code.

**Behaviour under real Android conditions**

6. **Durable offline mutation queue** ([14](14-offline-mutation-queue.md)) —
   TanStack's paused mutations die with the tab. Kwery persists them across
   process death, with idempotency keys, dead-lettering, and expiry.
7. **Rotation and brief app switches do not refetch**
   ([05](05-deduplication-observers.md)) — reattaching inside the grace window
   is a continuation, not a mount. Measured: zero extra requests, where TanStack
   semantics produce one per rotation.
8. **Leaving and returning mid-request joins the in-flight fetch**
   ([10](10-cancellation.md)) rather than cancelling and starting over.
9. **Retry jitter on by default** ([06](06-retries.md)) — un-jittered backoff
   synchronises a whole fleet into a thundering herd after a carrier-level blip.
10. **Connectivity requires a *validated* network**
    ([07](07-refetch-triggers.md)) — captive portals and connected-but-dead
    Wi-Fi otherwise report online while every request fails.
11. **Data Saver suppresses polling and prefetch, never a visible screen's data**
    ([07](07-refetch-triggers.md)).
12. **Bounded memory** ([04](04-caching-lifecycle.md)) — `maxEntries` (default
    500, LRU over inactive entries only) plus opt-in `onTrimMemory`. `gcTime`
    bounds the cache by time and nothing bounds it by size; a browser tab gets
    reloaded, an Android process can live for days.

**Ergonomics**

13. **Structured concurrency for cancellation** ([10](10-cancellation.md)) —
    no manual `AbortSignal` threading.
14. **Atomic persistence writes** ([15](15-persistence.md)) — a process killed
    mid-write leaves the previous snapshot wholly intact, rather than a
    half-written mixture. A row-based persister for large caches is future work.
15. **Seeded cache entries can later refetch** ([09](09-manual-cache.md)) — a
    `setQueryData`-created entry adopts a query function when first observed,
    instead of staying frozen forever.
16. **First-class test client** ([21](21-testing.md)) — virtual clock, request
    recording, controllable connectivity and focus. TanStack's testing guide is
    four pieces of configuration you must remember; Kwery's default is correct.

## How to use these documents

Each feature file follows the same shape:

1. **TanStack behaviour** — what the reference implementation does, cited to
   the vendored docs so claims are checkable.
2. **Kwery design** — the Kotlin API, with the reasoning for any divergence.
3. **Parity table** — item-by-item, so gaps are visible rather than implied.
4. **Deliberate divergences** — what we do differently and why.
5. **Open questions** — unresolved decisions blocking implementation.
6. **Definition of done** — the tests that must pass for the feature to ship.

## The three gates

A feature is complete only when all three have been passed, in order.

**Gate 1 — Spec.** This file. Passed when the design is written and every open
question is either resolved or explicitly deferred with a reason.

**Gate 2 — Tests.** Passed when every box in the file's "Definition of done" is
ticked and the suite is green. Start by reading the test names in the
corresponding TanStack file (see the "Port tests from" column) — they are a
behavioural checklist covering edge cases no design document anticipates. Port
each relevant case; record any deliberate omission in the parity table.

Gate 2 is also where the spec gets corrected. Implementation routinely proves a
design assumption wrong; when it does, **fix this file first**, then continue. A
roadmap file that no longer matches the code is worse than no roadmap.

**Gate 3 — Documentation.** Passed when `docs/<feature>.md` exists as
user-facing documentation with examples that compile.

Gate 3 comes after gate 2 without exception. Documentation describes behaviour
that has been proven, not behaviour that was intended — writing it earlier
produces confident documentation of bugs.

Update the status table above as each gate is passed. It is the single source of
truth for project state.
