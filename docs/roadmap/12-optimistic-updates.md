# 12 — Optimistic Updates & Rollback

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | planned |
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
val addTodo = client.mutation(AddTodoKey) {
    mutationFn = { input -> api.addTodo(input) }

    optimistic(TodoListKey) { current, input ->
        (current ?: emptyList()) + input.asOptimisticTodo()
    }
    // expands to: cancelQueries -> snapshot -> setQueryData
    //              -> restore snapshot on error
    //              -> invalidateQueries on settle
}
```

`optimistic()` is the single most valuable piece of API in this feature. It
removes the four-step ceremony, guarantees `cancelQueries` runs first, and makes
rollback automatic instead of hand-written. Users who need something more
elaborate still have raw `onMutate`/`onError`/`onSettled`.

### Concurrency

Multiple in-flight optimistic mutations against one key are the hard case:
snapshot-and-restore is wrong when mutation A rolls back to a snapshot that
already contained mutation B's optimistic write, silently discarding B.

TanStack's answer is `submittedAt` plus the UI approach. Kwery's `optimistic()`
helper will detect overlapping optimistic writes on the same key and, rather
than silently corrupting state, **re-derive** the cache value by replaying the
remaining in-flight optimistic updates over the last known server value. This
requires retaining the optimistic function per in-flight mutation, which the
helper form makes possible and the raw form does not.

This is a real improvement over TanStack, and also the most likely place for
Kwery to have subtle bugs. See OQ-1.

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
| `isPlaceholderData`-style optimistic flag | no | `isOptimistic` on `QueryState` | divergent (addition) |

## Open questions

- **OQ-1.** Is replay-based re-derivation correct in all cases? It assumes
  optimistic functions are pure and commutative enough to replay in submission
  order. A reorder-list mutation may not be. Needs adversarial tests, and a
  documented escape hatch to the raw callbacks when replay is inappropriate.
- **OQ-2.** Should `isOptimistic` be exposed on `QueryState`? It lets UIs ghost
  unconfirmed rows generically. Cost: another field, and it is only meaningful
  when the helper is used.
- **OQ-3.** What happens to an optimistic write when the app dies before the
  mutation completes? Ties directly into [14](14-offline-mutation-queue.md) —
  the optimistic value must either be persisted with the queued mutation or
  discarded on hydrate. Discarding is safer; persisting is what users expect.
  **This must be resolved jointly with 14 and 15.**

## Definition of done

- [ ] `optimistic()` helper implemented over raw callbacks.
- [ ] Test: optimistic value visible immediately, before the mutation resolves.
- [ ] Test: failure restores the exact prior value.
- [ ] Test: an in-flight refetch resolving *after* an optimistic write does not
      clobber it — the `cancelQueries` regression test.
- [ ] Test: two concurrent optimistic mutations on one key, first fails —
      second's optimistic value survives.
- [ ] Test: two concurrent optimistic mutations, both succeed, final state
      matches server truth after invalidation.
- [ ] Test: raw callback form still works for users bypassing the helper.
- [ ] OQ-3 resolved and its decision reflected in 14 and 15.
