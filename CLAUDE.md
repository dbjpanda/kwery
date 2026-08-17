# Kwery

A TanStack Query equivalent for Android/Kotlin: async server-state management
with caching, deduplication, stale-while-revalidate, mutations, offline support,
and a persisted cache that survives process death.

Public OSS library. Android-only, JVM-first core. Targets **parity with TanStack
Query v5**, diverging only where Kotlin or Android makes something genuinely
better — and recording every divergence explicitly.

## The three gates

**A feature is not done until all three gates are passed, in order. Never skip
ahead, and never mark a gate passed without the artifact existing.**

| Gate | Artifact | Passed when |
|---|---|---|
| 1. Spec | `docs/roadmap/NN-feature.md` | design written, open questions resolved or explicitly deferred |
| 2. Tests | test sources in the owning module | every box in that file's "Definition of done" is ticked and the suite is green |
| 3. Docs | `docs/feature.md` | user-facing documentation written, examples compile |

Rules:

- **Gate 2 before gate 3, always.** Documentation describes behaviour that has
  been proven, not behaviour that is intended. Writing docs for untested code
  produces confident documentation of bugs.
- **Never claim a gate is passed without running the verification.** For gate 2
  that means running the tests and reading the output. "Should pass" is not
  passing.
- **Update `docs/roadmap/README.md`'s status table** as gates are passed. It is
  the single source of truth for project state.
- If implementation reveals the spec was wrong, **go back and fix the spec
  first**, then continue. A roadmap file that no longer matches the code is
  worse than no roadmap.

## Reference material

`.reference/tanstack-query/` vendors TanStack Query at a pinned revision
(`dce04b5`, 2026-08-17). It is committed deliberately. Consult it rather than
working from memory — TanStack's behaviour has subtleties that are easy to
misremember, and a wrong parity claim is worse than an admitted gap.

- `docs/` — 494 markdown files. The framework-agnostic behaviour is in
  `docs/framework/react/guides/`; core API in `docs/reference/`.
- `packages/query-core/src/__tests__/` — **16,255 lines of behavioural tests.**
  This is the most valuable artifact in the repo for gate 2.

### Using TanStack's tests

`query-core` is the framework-agnostic layer, so its tests are almost entirely
portable. Before writing tests for a feature:

1. Find the corresponding test file (`query.test.tsx`, `queryObserver.test.tsx`,
   `mutations.test.tsx`, `hydration.test.tsx`, …).
2. Read the test *names* first — they are a behavioural checklist, and they
   encode edge cases no design document would think of. Real examples:
   *"should use the longest garbage collection time it has seen"*,
   *"cancelling a resolved query should not have any effect"*,
   *"the previous query status should be kept when refetching"*.
3. Port each relevant case, keeping the intent and adapting the mechanism.
4. **When a case is deliberately not ported, say so in the roadmap file's parity
   table** with the reason. Silent omission is how parity claims become false.

Do not port React-specific tests (`test-d` type tests, render/hook tests,
Suspense, SSR) — see the roadmap's non-goals.

## Layout

```
CLAUDE.md               this file
.reference/             vendored TanStack docs + tests (committed, pinned)
docs/roadmap/           gate 1 — one file per feature, 24 features, 4 tiers
docs/                   gate 3 — user-facing documentation
kwery-core/             pure Kotlin/JVM: cache, observers, retries, mutations
kwery-android/          lifecycle FocusManager, connectivity OnlineManager
kwery-compose/          rememberQuery / rememberMutation / rememberInfiniteQuery
kwery-persist/          persistence contracts + dehydrate/hydrate
kwery-persist-datastore/  DataStore-backed persister
kwery-persist-room/     Room-backed persister for larger caches
kwery-devtools/         inspection surface (post-v1)
kwery-test/             TestQueryClient — virtual clock, request recording
```

Start at `docs/roadmap/README.md`. It holds the status table, module layout,
positioning against Soil and Store5, non-goals, and the locked decisions.

## Locked architectural decisions

Do not silently violate these. Changing one means updating
`docs/roadmap/README.md` and every affected feature file.

- **AD-1 — JVM-pure core.** `kwery-core` has **no Android dependencies**. Android
  concerns enter through interfaces (`FocusManager`, `OnlineManager`) with no-op
  JVM defaults, implemented in `kwery-android`. This keeps time-dependent cache
  logic testable with a virtual clock and no Robolectric.
- **AD-2 — Flow-first surface.** The core primitive is
  `QueryObserver` → `Flow<QueryState<T>>`. `kwery-compose` is a **thin adapter**
  over it. If a behaviour exists only in `kwery-compose`, it is in the wrong
  module.
- **AD-3 — Hybrid query keys.** `interface QueryKey<T> { val parts: List<Any?> }`.
  Typed for compile-time safety; `parts` preserves prefix matching and stable
  serialization.
- **AD-4 — Two orthogonal status axes.** `status` (pending/error/success) and
  `fetchStatus` (fetching/paused/idle) stay separate. **Never collapse them into
  one sealed class** — that loses `success`+`fetching` (background refetch) and
  `pending`+`paused` (cold start, offline), which are the states Android
  actually hits.

## Blocking constraint

**`docs/roadmap/05-deduplication-observers.md` requires a spike before any cache
implementation begins.** Observer ref-counting for `gcTime` on a `Flow` surface
is unresolved — `SharingStarted.WhileSubscribed` introduces a second, independent
timeout, and getting the interaction wrong either leaks entries forever or evicts
data still on screen. Every other feature depends on eviction semantics.

Do not start `kwery-core`'s cache until that spike resolves and its
recommendation is recorded in the roadmap file.

## Conventions

**Tests**

- No real `delay()`. Time is driven through the injectable `TimeSource` and
  `kotlinx-coroutines-test`. A suite that takes minutes gets skipped.
- Assert **request counts**, not just final state. Most meaningful claims about a
  caching library ("deduplicated", "did not refetch", "prefetch was a no-op") are
  request-count claims.
- Kwery's own suite uses `kwery-test`'s `TestQueryClient`. Dogfooding it is how
  we find out whether it is good.
- Name tests after the behaviour, following TanStack's style:
  `should keep previous data while refetching`, not `testRefetch2`.

**Code**

- `kwery-core` stays dependency-light: coroutines, and kotlinx-serialization only
  where persistence requires it. Every added dependency is a cost users pay.
- Public API gets KDoc, including the non-obvious parts — especially the
  `staleTime`/`gcTime` distinction and the two status axes, which are the two
  things every user misunderstands.
- Explicit API mode on for all published modules.
- Binary compatibility validator on before the first release.

**Commits**

- One feature gate per commit where practical. A gate-2 commit contains tests
  and the implementation that makes them pass; a gate-3 commit contains docs.
- Re-vendoring `.reference/` lands as its **own** commit, so the upstream delta
  is reviewable separately from Kwery changes.
