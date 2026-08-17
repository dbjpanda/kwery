# Kwery Roadmap — TanStack Query Parity

Kwery is a TanStack Query equivalent for Android/Kotlin: async server-state
management with caching, deduplication, stale-while-revalidate, mutations,
offline support, and a persisted cache that survives process death.

This folder tracks **feature parity with TanStack Query v5**, one file per
feature area. The reference implementation's documentation is vendored at
`.reference/tanstack-query/docs/` so parity can be checked offline and
re-checked when TanStack ships changes.

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
- **AD-3 — Hybrid query keys.** `interface QueryKey { val parts: List<Any?> }`.
  Typed data classes give compile-time safety; `parts` preserves TanStack's
  prefix matching, filters, and stable serialization.
- **AD-4 — Two orthogonal status axes.** `status` (pending/error/success) and
  `fetchStatus` (fetching/paused/idle) are separate, exactly as in TanStack.
  Never collapsed into a single sealed class.

## Module layout

```
kwery-core         pure Kotlin/JVM — cache, observers, retries, mutations
kwery-android      lifecycle-based FocusManager, connectivity OnlineManager
kwery-compose      rememberQuery / rememberMutation / rememberInfiniteQuery
kwery-persist      persistence contracts + dehydrate/hydrate
kwery-persist-datastore   DataStore-backed persister
kwery-persist-room        Room/SQLite-backed persister (larger caches)
kwery-devtools     inspection surface (post-v1)
kwery-test         virtual-clock test harness for consumers
```

## Feature index

Every feature passes **three gates in order**: spec written → tests green →
documentation published. See [`CLAUDE.md`](../../CLAUDE.md) for the rules.

Gate status: ○ not started · ◐ in progress · ● done · — not applicable

The "Port tests from" column names the file in
`.reference/tanstack-query/packages/query-core/src/__tests__/` whose cases
should be ported for gate 2. Cases deliberately not ported must be recorded in
that feature's parity table with a reason.

### Tier 1 — v1 core (irreducible)

| # | Feature | Port tests from | Spec | Tests | Docs |
|---|---|---|:--:|:--:|:--:|
| 01 | [Query keys](01-query-keys.md) | `utils.test.tsx`, `queryCache.test.tsx` | ● | ○ | ○ |
| 02 | [Query functions](02-query-functions.md) | `query.test.tsx` | ● | ○ | ○ |
| 03 | [Query state & status axes](03-query-state.md) | `queryObserver.test.tsx` | ● | ○ | ○ |
| 04 | [Caching lifecycle](04-caching-lifecycle.md) | `query.test.tsx`, `queryCache.test.tsx` | ● | ○ | ○ |
| 05 | [Deduplication & observers](05-deduplication-observers.md) | `queryObserver.test.tsx`, `queriesObserver.test.tsx` | ◐ | ○ | ○ |
| 06 | [Retries & backoff](06-retries.md) | `query.test.tsx` | ● | ○ | ○ |
| 07 | [Refetch triggers](07-refetch-triggers.md) | `focusManager.test.tsx`, `onlineManager.test.tsx` | ● | ○ | ○ |
| 08 | [Invalidation & filters](08-invalidation-filters.md) | `queryClient.test.tsx`, `utils.test.tsx` | ● | ○ | ○ |
| 09 | [Manual cache access](09-manual-cache.md) | `queryClient.test.tsx`, `queryObserver.test.tsx` | ● | ○ | ○ |
| 10 | [Cancellation](10-cancellation.md) | `query.test.tsx` | ● | ○ | ○ |

### Tier 2 — v1 headline features

| # | Feature | Port tests from | Spec | Tests | Docs |
|---|---|---|:--:|:--:|:--:|
| 11 | [Mutations](11-mutations.md) | `mutations.test.tsx`, `mutationCache.test.tsx`, `mutationObserver.test.tsx` | ● | ○ | ○ |
| 12 | [Optimistic updates & rollback](12-optimistic-updates.md) | `mutations.test.tsx` | ● | ○ | ○ |
| 13 | [Network mode & offline pause](13-network-mode.md) | `onlineManager.test.tsx`, `query.test.tsx` | ● | ○ | ○ |
| 14 | [Offline mutation queue](14-offline-mutation-queue.md) | `mutations.test.tsx`, `hydration.test.tsx` | ● | ○ | ○ |
| 15 | [Persistence & hydration](15-persistence.md) | `hydration.test.tsx` | ● | ○ | ○ |
| 16 | [Infinite & paginated queries](16-infinite-queries.md) | `infiniteQueryBehavior.test.tsx`, `infiniteQueryObserver.test.tsx` | ● | ○ | ○ |

### Tier 3 — v1 integration surfaces

| # | Feature | Port tests from | Spec | Tests | Docs |
|---|---|---|:--:|:--:|:--:|
| 17 | [Compose bindings](17-compose-bindings.md) | — (React-specific) | ● | ○ | ○ |
| 18 | [ViewModel integration](18-viewmodel-integration.md) | — (Kwery-specific) | ● | ○ | ○ |
| 19 | [Dependent & parallel queries](19-dependent-parallel.md) | `queriesObserver.test.tsx` | ● | ○ | ○ |
| 20 | [Prefetching](20-prefetching.md) | `queryClient.test.tsx` | ● | ○ | ○ |
| 21 | [Testing support](21-testing.md) | — (Kwery-specific) | ● | ○ | ○ |

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

Places where Kwery should deliberately do better, not just match:

1. **Validate `gcTime` ≥ persistence `maxAge` at construction.** TanStack
   documents this constraint and lets you violate it silently, causing the
   persisted cache to be garbage collected earlier than expected. Kwery throws.
2. **Typed query keys.** Compile-time safety on the data type associated with a
   key, so `setQueryData` and `select` cannot be given the wrong type.
3. **Durable offline mutation queue.** TanStack's paused mutations live in
   memory and die with the tab. Kwery persists the queue across process death —
   the difference between a nice-to-have and something you can ship a
   write-heavy offline app on.
4. **Structured concurrency for cancellation.** Cancellation is cooperative and
   automatic via `CoroutineScope`, rather than TanStack's manual `AbortSignal`
   threading.

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
