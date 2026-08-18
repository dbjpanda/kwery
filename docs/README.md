# Kwery documentation

User-facing documentation. This is **gate 3**: see
[`CLAUDE.md`](https://github.com/dbjpanda/kwery/blob/main/CLAUDE.md) for what
the gates are.

A page appears here only after its feature's tests are green. Documentation
describes behaviour that has been proven, not behaviour that was intended.

Design rationale, parity tables and open questions live in the project's
roadmap, which is working material and deliberately not published — it argues
with prior art, and these pages do not unless a reader migrating
from it needs the comparison. Where a decision's reasoning matters to someone
*using* the library, it is inlined here instead: see
[deduplication](deduplication.md) for the observer model and the measurements
that chose it.

<div class="grid cards" markdown>

-   :material-rocket-launch:{ .lg .middle } **Start here**

    ---

    Read data, give it identity, and understand the two status axes that
    every other page assumes.

    [:octicons-arrow-right-24: Queries](queries.md)

-   :material-refresh:{ .lg .middle } **Keeping data fresh**

    ---

    Deduplication, the two clocks, invalidation, retries, and the four
    automatic refetch triggers.

    [:octicons-arrow-right-24: Caching](caching.md)

-   :material-pencil:{ .lg .middle } **Writing**

    ---

    Mutations, optimistic updates, and writes that survive process death.

    [:octicons-arrow-right-24: Mutations](mutations.md)

-   :material-android:{ .lg .middle } **In your app**

    ---

    Compose bindings, the ViewModel pattern, testing, and inspecting the
    cache.

    [:octicons-arrow-right-24: Compose](compose.md)

</div>

## Pages

**Start here**

- [Queries](queries.md) — reading data, and everything the library does around it
- [Query keys](query-keys.md) — identity, and why keys are typed
- [Query state](query-state.md) — the two status axes, and why one enum is not enough

**Keeping data fresh**

- [Deduplication and observers](deduplication.md) — sharing, the grace window, eviction
- [Caching](caching.md) — staleTime, gcTime, and why they are different clocks
- [Invalidation](invalidation.md) — making writes visible to reads
- [Reading and writing the cache](manual-cache.md) — the escape hatches, and when not to reach for them
- [Retries](retries.md) — what to retry, what never to retry, and jitter
- [Cancellation](cancellation.md) — what happens when a screen goes away
- [Refetching](refetching.md) — the four automatic triggers
- [Prefetching](prefetching.md) — starting the request before the screen opens

**Writing**

- [Mutations](mutations.md) — writes, their lifecycle, and scopes
- [Optimistic updates](optimistic-updates.md) — showing a write before it lands
- [Offline writes](offline.md) — durable writes that survive process death
- [Persistence](persistence.md) — the query cache across process death

**Using it from your app**

- [Compose](compose.md) — `rememberQuery` and friends
- [ViewModels](viewmodels.md) — the `stateIn` pattern, measured
- [Testing](testing.md) — `TestQueryClient`, and why request counts are the assertion
- [Inspecting the cache](devtools.md) — snapshots, and why a query refetched

**Composing queries**

- [Parallel and dependent queries](parallel-queries.md) — `combine`, `aggregate`, and avoiding waterfalls

**Lists**

- [Infinite queries](infinite-queries.md) — accumulating pages
- [Paginated queries](paginated-queries.md) — pages that replace each other

Pages are named after the feature rather than its roadmap number, since readers
do not care about build order.

Every page here has passed all three gates: its feature is specified, its tests
are green, and the page describes behaviour that was proven rather than
intended. Offline writes and persistence were the last two to get there, once
the Room-backed store and the device tests landed.

Writing the docs keeps finding real bugs, which is the ordering rule earning its
keep: `offline.md` justified the ordering rule: the page's
idempotency example referenced a value the API did not expose, which made the
at-least-once guarantee unusable from the one place that needs it. That gap was
invisible from the tests, which never had to *write the call*. `DurableMutationScope`
exists because of it.

## How these pages are kept honest

Examples rot silently: nothing compiles a fenced code block, so a renamed
parameter or a method that only ever existed in a design sketch sits here
looking authoritative. That has happened three times in this project — a
`currentQueuedMutationId` that did not exist, a `PlaceholderData.KeepPrevious`
that was never built, and a `prefetchQuery(key, staleTime, fetcher)` overload
lifted from a roadmap file rather than the code.

`docs-lint` runs as part of the normal build and checks two things:

- **Every Kwery identifier** in these pages exists — methods called on a client
  or query, enum constants, and Kwery's own type names — against the committed
  `.api` dumps and the sources. Capitalisation counts, which the dumps alone
  cannot tell you: `val Exponential` and `val exponential` compile to the same
  getter.
- **Every named argument** names a real parameter, with the accepted list shown
  when one does not. Parameter names live only in the sources, so this reads
  those; it is what would have caught
  `prefetchQuery(key, staleTime, fetcher)`, a signature taken from a design
  sketch that never existed in the code.

It is still a lint rather than a compiler — it will not catch a wrong argument
*order*, or a type error — but the two classes it does catch are the two that
have actually bitten this project.

## Writing a page

Each page should answer, in order:

1. **What problem does this solve?** One paragraph, no API.
2. **The simplest thing that works.** A complete, compiling example — not a
   fragment with `// ...` where the hard part goes.
3. **The options**, with defaults stated and the reason each default was chosen.
4. **What goes wrong.** The failure modes, misconfigurations, and gotchas found
   while writing the tests. This is the section that makes documentation worth
   reading, and it can only be written after gate 2 — which is why gate 3 comes
   last.
5. **Related pages.**

Rules:

- **Examples must compile.** Extract them from the test suite or the sample app
  where possible, so they cannot rot silently.
- State defaults explicitly. "`staleTime` defaults to zero, so data is
  considered stale immediately" — not "configure `staleTime` as needed".
- Document the two things every user gets wrong, wherever they are relevant:
  the `staleTime` vs `gcTime` distinction, and `status` vs `fetchStatus`.
- Do not restate the specs' design rationale. A reader here wants to use the
  library, not to know what was considered and rejected — unless the reasoning
  changes how they should use it, in which case it belongs here and nowhere
  else.
