# Optimistic updates

## The problem

Tapping a checkbox should tick it immediately. Waiting 300 ms for a server to
agree makes an app feel broken, even though nothing is wrong.

So you update the cache before the server confirms, and undo it if the server
refuses. The hard part is not the happy path — it is undoing correctly when two
writes are in flight at once.

## The simplest thing that works

```kotlin
val toggle = client.optimisticMutation(
    key = TodoListKey,
    apply = { todos, id -> todos?.map { if (it.id == id) it.copy(done = !it.done) else it } },
) { id -> api.toggle(id) }

toggle.mutate(todoId)   // the checkbox ticks now
```

That expands to the four steps this needs, in the order that makes them correct:

1. **Cancel in-flight fetches for the key.** Not optional — a refetch that
   resolves after your write would overwrite it with older server data, and the
   checkbox would visibly untick itself.
2. Apply the transform to the cached value.
3. On failure, drop the transform.
4. Once the **last** in-flight write for that key settles, invalidate so the
   cache reconverges on the server.

## Why this is not snapshot-and-restore

The obvious implementation snapshots the value in `onMutate` and restores it in
`onError`. It is silently wrong the moment two writes overlap:

```
A starts:  snapshot [x]        cache becomes [A(x)]
B starts:  snapshot [A(x)]     cache becomes [B(A(x))]
A fails:   restore  [x]        ← B's write is gone, and B is still in flight
```

B may still succeed, and the user's second tap has vanished from the screen.

Kwery keeps the last known **server** value plus an ordered list of in-flight
transforms, and re-derives the cached value by replaying them. Removing A simply
replays B over the base. Nothing is lost, and no ordering assumption is needed.

## What `apply` must satisfy

**It must be a pure function of the value it is given.** It may be replayed
against a different input than the one present when the write was submitted.

```kotlin
// Fine — identifies its target by id.
apply = { todos, id -> todos?.map { if (it.id == id) it.copy(done = true) else it } }

// Not fine — depends on position, which another in-flight write may have moved.
apply = { todos, _ -> todos?.toMutableList()?.also { it.removeAt(0) } }
```

For transforms that genuinely depend on order — reordering a list, say — use the
raw `onMutate`/`onError`/`onSettled` callbacks on
[`MutationOptions`](mutations.md) instead, and accept the concurrency caveat.

## Showing that something is unconfirmed

```kotlin
val state = rememberQuery(TodoListKey) { api.todos() }
if (state.isOptimistic) {
    // At least one write against this key has not been confirmed yet.
}
```

`isOptimistic` lets a list ghost unconfirmed rows generically, instead of every
screen tracking which of its own mutations are pending.

## What goes wrong

**Invalidation waits for the last write, not each one.** Invalidating in every
mutation's `onSettled` — which is what TanStack's examples do — refetches server
truth while your *other* optimistic write is still pending, clobbering it.
Kwery invalidates only when the last in-flight write for the key clears. If you
write the callbacks by hand, you have to handle this yourself.

**A successful write does not flicker.** When the server accepts, the transform
is folded into the base rather than discarded, so the value does not revert and
then move forward again when the refetch lands. This is easy to get wrong by
treating success and failure the same way.

**An unconfirmed write is never persisted.** If you also persist the cache, an
optimistic value is excluded from the snapshot — otherwise it would come back
after a cold start looking like something the server had accepted.

**A failed write rolls back silently.** The value reverts; the user is not told.
Show `state.error` from the mutation if the rollback would otherwise be
confusing — a checkbox that unticks itself with no explanation is worse than one
that never ticked.

## Related

- [Mutations](mutations.md) — the lifecycle this builds on
- [Offline writes](offline.md) — optimistic previews do not yet survive a cold
  start, though the writes themselves do
- [Query state](query-state.md) — `isOptimistic` and the other flags
