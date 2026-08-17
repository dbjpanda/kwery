# Kwery documentation

User-facing documentation. This is **gate 3** — see [`CLAUDE.md`](../CLAUDE.md).

A page appears here only after its feature's tests are green. Documentation
describes behaviour that has been proven, not behaviour that was intended.

For design rationale, parity tables, and open questions, see
[`roadmap/`](roadmap/) — that is gate 1 and a different audience. Roadmap files
argue with TanStack Query; these pages do not mention it unless a reader
migrating from it needs the comparison.

## Pages

- [Query state](query-state.md) — the two status axes, and why one enum is not enough
- [Mutations](mutations.md) — writes, their lifecycle, and scopes
- [Optimistic updates](optimistic-updates.md) — showing a write before it lands
- [Offline writes](offline.md) — durable writes that survive process death *(feature still open)*
- [Persistence](persistence.md) — the query cache across process death *(feature still open)*
- [Infinite queries](infinite-queries.md) — accumulating pages
- [Paginated queries](paginated-queries.md) — pages that replace each other

Pages land as features pass gate 2, named after the feature rather than its
roadmap number since readers do not care about build order. Still to write, and
each blocked on its feature's gate 2:

`queries.md` · `query-keys.md` · `caching.md` · `retries.md` ·
`refetching.md` · `invalidation.md` · `compose.md` · `viewmodels.md` ·
`prefetching.md` · `testing.md`

Two pages are marked *feature still open*: everything they describe is tested,
but their features have gate-2 items outstanding (a Room-backed store, device
tests), so neither counts as gate 3 passed. A page describing proven behaviour
is useful before the feature is finished; the gate is what says the feature is
done.

Writing `offline.md` immediately justified the ordering rule: the page's
idempotency example referenced a value the API did not expose, which made the
at-least-once guarantee unusable from the one place that needs it. That gap was
invisible from the tests, which never had to *write the call*. `DurableMutationScope`
exists because of it.

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
- Do not restate the roadmap's design rationale. A reader here wants to use the
  library, not to know what was considered and rejected.
