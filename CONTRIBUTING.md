# Contributing to Kwery

## First: fetch the reference material

Kwery targets behavioural parity with [TanStack Query](https://tanstack.com/query/latest),
and that parity is checked against TanStack's own documentation and test suite
rather than against anyone's memory of how it behaves.

That material is **not committed** to this repository. Fetch it once after
cloning:

```sh
./scripts/vendor-reference.sh
```

This puts TanStack Query's `docs/` and its `query-core` test suite under
`.reference/`, at the exact revision recorded in the script. `.reference/` is
gitignored, so it never appears in a diff and never bloats the repository.

You need it before doing any test work, and the links from the specs
into `.reference/` only resolve once you have run it.

| | |
|---|---|
| `.reference/tanstack-query/docs/` | 494 markdown files. Framework-agnostic behaviour is under `docs/framework/react/guides/`. |
| `.reference/tanstack-query/packages/query-core/src/__tests__/` | ~16,000 lines of behavioural tests. `query-core` is the framework-agnostic layer `kwery-core` mirrors, so these are close to an executable specification. |

Bumping the pinned revision is a deliberate act, not routine maintenance:
re-check every parity table in the specs against the upstream diff, and
land the bump as its own commit so the behavioural delta is reviewable on its
own.

## Building

```sh
./gradlew build      # compile + test
./gradlew apiCheck   # public API has not changed unintentionally
./gradlew apiDump    # accept an intentional public API change
```

Requires JDK 17. Published artifacts target JVM 11.

**Do not upgrade Gradle past 9.5.x.** The reason is non-obvious and is recorded
in [CLAUDE.md](CLAUDE.md) — briefly, Gradle 9.6+ forces AGP 9.x, whose built-in
Kotlin silently disables KSP and stops `apiCheck` covering Android modules at
all.

## The three gates

A feature is complete only when all three have been passed, **in order**:

1. **Spec** — a design document with the open questions resolved, or explicitly
   deferred with a reason. Specs live in `docs/roadmap/`, which is **not part of
   this repository**: it is working material, and publishing it would put four
   tiers of open questions in front of people who want to use the library. Ask
   for it if you are taking on a feature.
2. **Tests** — every box in that spec's "Definition of done" ticked, suite green.
3. **Docs** — `docs/<feature>.md`, user-facing, with examples that compile.

Gate 3 never precedes gate 2. Documentation describes behaviour that has been
proven, not behaviour that was intended — the "what goes wrong" section of a
docs page can only be written after the tests exist, and it is the section
readers actually need.

Current status is summarised in [RELEASE.md](RELEASE.md): what passes all
three gates, what was deliberately not built, and what needs a device.

## Writing tests

- **Start from TanStack's tests**, not from the design document. Read the test
  *names* first — they encode edge cases no spec anticipates. Real examples that
  changed Kwery's implementation: *"should use the longest garbage collection
  time it has seen"*, *"the previous query status should be kept when
  refetching"*, *"cancelling a rejected query should not have any effect"*.
- **When a case is deliberately not ported, record it in the feature's parity
  table with a reason.** Silent omission is how a parity claim becomes false.
- **No real `delay()`.** Time runs through the injectable `TimeSource` and
  `kotlinx-coroutines-test`. A suite that takes minutes is a suite that gets
  skipped.
- **Assert request counts, not just final state.** Most meaningful claims about
  a caching library — "deduplicated", "did not refetch", "rotation is free" —
  are request-count claims. `TestQueryClient` records them.
- **Prove the test can fail.** Break the code deliberately and watch it go red
  before trusting it. Several of Kwery's most important guards were confirmed
  this way, and a green test that cannot fail is decoration.

## Code

- `kwery-core` has **no Android dependencies**, ever. Android concerns enter
  through interfaces implemented in `kwery-android`.
- Explicit API mode and `allWarningsAsErrors` are on. Public API is permanent
  once released; `apiCheck` makes every change to it a reviewed diff.
- Public API gets KDoc — especially the two things every user misunderstands:
  `staleTime` vs `gcTime`, and `status` vs `fetchStatus`.

## Commits

One gate per commit where practical. A re-vendor of `.reference/` lands as its
own commit so the upstream delta stays reviewable separately from Kwery changes.
