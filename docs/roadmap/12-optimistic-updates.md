# 12 — Optimistic Updates & Rollback

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | **gate 2 complete** — replay model verified by mutation testing |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/optimistic-updates.md`](../../.reference/tanstack-query/docs/framework/react/guides/optimistic-updates.md), [`guides/updates-from-mutation-responses.md`](../../.reference/tanstack-query/docs/framework/react/guides/updates-from-mutation-responses.md) |
| **Depends on** | 08 Invalidation, 09 Manual cache, 11 Mutations |

## TanStack behaviour

Two approaches, and the docs are clear that most people should use the simpler one.

**Via the UI (simpler).** Render `mutation.variables` alongside the query's data
while `isPending`, then `invalidateQueries` in `onSettled`. No cache writes, no
rollback logic. Works only where the mutation and the query render together —
otherwise `useMutationState` bridges the gap. `variables` survive an error, so a
retry button is easy.

**Via the cache (powerful).** In `onMutate`: cancel in-flight queries, snapshot
current data, write the optimistic value, return the snapshot. In `onError`:
restore the snapshot. In `onSettled`: invalidate.

The `cancelQueries` call in `onMutate` is not optional — an in-flight refetch
that resolves after the optimistic write will overwrite it with stale server
data, producing a UI that flickers back to the old value.

## Kwery design

Both approaches are supported; the cache approach gets a helper because its
four-step choreography is where bugs live.

### Via the UI

Falls out of [11](11-mutations.md): `MutationState.variables` is exposed, and
`client.mutationStates(filters)` covers the cross-screen case.

```kotlin
val pending by addTodo.state.collectAsState()
LazyColumn {
    items(todos) { TodoRow(it) }
    if (pending.status == MutationStatus.Pending) {
        item { TodoRow(pending.variables!!.asOptimisticTodo(), ghosted = true) }
    }
}
```

### Via the cache

The raw form matches TanStack. The helper encodes the correct order so it cannot
be got wrong:

```kotlin
val toggle = client.optimisticMutation(
    key = TodoListKey,
    apply = { todos, id -> todos?.map { if (it.id == id) it.copy(done = !it.done) else it } },
) { id -> api.toggle(id) }
```

It expands to: cancel in-flight fetches → register the transform → drop or
commit it when the mutation settles → invalidate once the **last** in-flight
write clears.

`optimisticMutation` is the most valuable piece of API in this feature. It
removes the four-step ceremony, guarantees `cancelQueries` runs first, and makes
rollback automatic instead of hand-written. Users who need something more
elaborate still have raw `onMutate`/`onError`/`onSettled`.

### Concurrency

Multiple in-flight optimistic mutations against one key are the hard case:
snapshot-and-restore is wrong when mutation A rolls back to a snapshot that
already contained mutation B's optimistic write, silently discarding B.

TanStack's answer is `submittedAt` plus the UI approach. Kwery keeps the last
known server value plus an **ordered list of in-flight transforms**, and
re-derives the cached value by replaying them. Removing a failed write simply
replays whatever remains; nothing is lost and no ordering assumption is needed.

Retaining the transform per in-flight mutation is what makes this possible,
which the helper form allows and the raw callback form does not.

Implemented and verified — see OQ-1 for the refinement the implementation
forced, and the purity requirement it places on `apply`.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| UI approach via `variables` | yes | yes | planned |
| `variables` survive error | yes | yes | planned |
| Cross-component mutation state | `useMutationState` | `client.mutationStates` | planned |
| Cache approach via `onMutate` | yes | yes | planned |
| Snapshot/rollback | manual | manual **or** `optimistic()` | divergent (better) |
| `cancelQueries` before write | manual, documented | enforced by helper | divergent (better) |
| Invalidate on settle | manual | automatic in helper | divergent (better) |
| Concurrent optimistic updates | `submittedAt`, UI approach | replay-based re-derivation | divergent (better) |
| `isPlaceholderData`-style optimistic flag | no | `isOptimistic` on `QueryState` | done |
| Committed transform folded into base (no success flicker) | n/a | yes | divergent (better) |
| Invalidation deferred until the last write settles | no — each `onSettled` invalidates | yes | divergent (better) |

## Open questions

- **OQ-1.** ~~Is replay-based re-derivation correct?~~ **Closed: yes, with a
  stated requirement on `apply`, and one refinement the implementation forced.**

  Verified by mutation testing: replacing replay with naive snapshot-and-restore
  breaks three tests, including the headline one — a failing write discarding a
  concurrent still-pending write.

  **The refinement.** Discarding a transform on *success* was wrong. It reverted
  the cache to the pre-mutation value and then moved it forward again when the
  refetch landed, producing a visible flicker on the happy path. A committed
  transform is now folded **into** the base, since the server accepted it and
  that value is truth. Failure still simply drops the transform.

  **The requirement.** `apply` must be a pure function of the value it is given,
  because it may be replayed against a different input than the one present at
  submission. Transforms that identify their target by id — the normal case —
  satisfy this. Position-dependent ones (reordering a list) generally do not,
  and should use the raw `MutationOptions` callbacks instead. Documented on
  `optimisticMutation`.
- **OQ-2.** ~~Expose `isOptimistic` on `QueryState`?~~ **Closed: yes, shipped.**
  This is the batched field addition [03](03-query-state.md) reserved for this
  feature. It lets a list ghost unconfirmed rows generically instead of every
  screen tracking which of its own mutations are pending. Also available as
  `client.isOptimistic(key)` for non-observing callers.
- **OQ-3.** *(still open, and correctly so)* What happens to an optimistic write
  when the app dies before the mutation completes? The registry is in-memory, so
  today the optimistic value is simply lost and the persisted cache holds the
  pre-mutation value — safe, but not what a user expects after tapping.

  Resolving this belongs with [14](14-offline-mutation-queue.md), which is where
  mutations gain durable identity. Note the constraint 14 already carries:
  resume must **not** re-run `onMutate`, so a persisted optimistic value has to
  be restored from storage rather than recomputed by replaying the transform.

## Definition of done

- [x] `optimisticMutation` helper implemented over the raw callbacks.
- [x] Test: optimistic value visible immediately, before the mutation resolves.
- [x] Test: failure rolls back to the prior value.
- [x] Test: an in-flight refetch resolving *after* an optimistic write does not
      clobber it — the `cancelQueries` regression test.
- [x] Test: two concurrent writes, first fails — the second's still-pending
      write survives. **Verified by mutation**: naive snapshot-and-restore
      fails this.
- [x] Test: two concurrent writes, both succeed, both survive.
- [x] Test: two concurrent writes, both fail, both roll back to genuine server
      state — the second must not mistake the first's optimistic value for truth.
- [x] Test: invalidation is deferred until the **last** write settles, so a
      refetch cannot clobber a still-pending write.
- [x] Test: `isOptimistic` clears on both the success and failure paths.
- [ ] Test: raw callback form still works for users bypassing the helper.
      **Deferred** — the helper is built on the public `MutationOptions`
      callbacks, which feature 11 already covers.
- [ ] OQ-3 (optimistic writes across process death) — carried to
      [14](14-offline-mutation-queue.md).
