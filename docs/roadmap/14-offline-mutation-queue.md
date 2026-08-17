# 14 — Offline Mutation Queue

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | **gate 2 complete** for the queue; Android store pending |
| **Module** | `kwery-core`, `kwery-persist` |
| **TanStack source** | [`guides/mutations.md`](../../.reference/tanstack-query/docs/framework/react/guides/mutations.md) (Persist mutations), [`reference/QueryClient.md`](../../.reference/tanstack-query/docs/reference/QueryClient.md) |
| **Depends on** | 11 Mutations, 12 Optimistic updates, 13 Network mode, 15 Persistence |
| **Risk** | **High — correctness here means not losing user writes** |

Where Kwery should decisively beat TanStack. In a browser tab, a paused mutation
dying with the tab is tolerable. On Android, the OS kills your process routinely
and a lost write is a lost write.

## TanStack behaviour

- A mutation failing because the device is offline is retried **in the same
  order** on reconnect.
- Paused mutations can be `dehydrate`d and `hydrate`d, then resumed with
  `resumePausedMutations()`.
- **Functions cannot be serialised.** Only mutation *state* persists. After
  hydration the component that owned the mutation may not exist, so
  `resumePausedMutations()` fails with `No mutationFn found` unless the function
  was registered globally via `setMutationDefaults(mutationKey, {mutationFn})`.
- Mutation scopes (`scope.id`) serialise execution within a scope.

That `setMutationDefaults` requirement is not a detail — it is the entire
architectural constraint. **A persisted mutation queue only works if mutation
functions are registered against keys at application start, independent of any
screen.**

## Kwery design

The constraint above becomes a first-class registry rather than a documented
workaround:

```kotlin
val queue = OfflineQueue(applicationScope, options, onlineManager, timeSource) {
    register(AddTodoKey) { input -> api.addTodo(input) }
    register(DeleteTodoKey) { id -> api.deleteTodo(id) }
}

queue.submit(AddTodoKey, AddTodoInput("Buy milk"))   // durable, returns immediately
queue.resume()                                        // after a cold start
```

Registration happens at construction, away from any screen, because a resumed
write has no UI to get its function from. Submitting an unregistered key fails
**immediately, at the call site**, rather than at resume time in the field —
which converts TanStack's most confusing runtime error into one a developer
sees on their first run.

**`submit` returns as soon as the write is on disk, not when it is delivered.**
A user tapping "save" while offline must not have their coroutine parked until
connectivity returns, possibly for hours; delivery runs on the queue's own
scope. This was found by a test that hung.

### Queue semantics

Decisions this feature must make explicitly, because getting them wrong silently
loses or duplicates user data:

- **Ordering.** FIFO within a `MutationScope`; scopes proceed independently.
  Global FIFO is simpler but serialises unrelated writes needlessly.
- **Durability.** Variables are serialised via kotlinx-serialization; the queue
  is written **before** the first attempt, not after failure. A process death
  between "user tapped save" and "first network attempt" must not lose the write.
- **At-least-once.** Delivery is at-least-once, not exactly-once. A write may
  have reached the server before the process died. Kwery **cannot** fix that
  alone, so `QueuedMutation.id` is a stable UUID minted at enqueue and surviving
  restarts unchanged — send it as an idempotency key so the server can recognise
  a replay.
- **Poison messages.** A mutation failing permanently (e.g. a 400) must not
  retry forever and block its scope. After a configurable attempt ceiling it
  moves to a **dead-letter** state, is removed from the active queue, and is
  surfaced via `client.failedMutations()` so the app can show the user.
- **Expiry.** Queued mutations older than a configurable `maxAge` (default 7
  days) are dead-lettered rather than replayed — replaying a week-old edit
  against changed server state is usually worse than dropping it.

### Resume

```kotlin
// after hydration completes
client.resumePausedMutations()
```

Resume must be sequenced **after** cache hydration ([15](15-persistence.md)), or
optimistic writes will be applied to an empty cache.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Offline mutations retried in order | yes | FIFO per scope | planned |
| `resumePausedMutations` | yes | yes | planned |
| Dehydrate/hydrate paused mutations | yes | yes | planned |
| Function registry required | `setMutationDefaults` | `mutations { register(…) }` | planned |
| Missing fn detected | runtime, on resume | **at the submit call site** | divergent (better) |
| Survives process death | no (tab-scoped) | **yes** | divergent (better) |
| Queue written before first attempt | no | yes | divergent (better) |
| Idempotency key | no | `DurableMutationScope.idempotencyKey`, stable across restarts | done |
| Dead-letter for poison mutations | no | yes, with a reason | divergent (addition) |
| Pending-write count for the UI | no | `pending: StateFlow<Int>` | divergent (addition) |
| Queue entry expiry | no | `maxAge`, default 7 days | divergent (addition) |
| Scoped serial execution | yes | yes | planned |

## Open questions

- **OQ-1.** ~~One store or two?~~ **Closed: two, and they share no code path.**
  `MutationQueueStore` is a separate interface from `QueryPersister`. The cache
  is disposable — expired, busted or corrupt snapshots are discarded wholesale —
  and applying that policy to pending user writes would silently destroy work a
  user believes is saved. A `buster` bump on an app release must never drop the
  queue.
- **OQ-2.** ~~Attempt count, elapsed time, or both?~~ **Closed: both.**
  `maxAttempts` (default 5) catches a permanently failing write — a 400 that
  will never succeed — before it blocks its scope forever. `maxAge` (default 7
  days) catches one that simply never got a network. They are different
  failures and neither subsumes the other.
- **OQ-3.** ~~Expose a pending count?~~ **Closed: yes, shipped** as
  `OfflineQueue.pending: StateFlow<Int>`. It is the visible half of offline
  support and nearly free once the queue exists.
- **OQ-4.** *(still open)* Are optimistic writes persisted alongside the queued
  mutation, so a cold start shows the user's pending edit? Currently **no**: the
  optimistic registry is in memory, and [15](15-persistence.md) deliberately
  excludes unconfirmed writes from the cache snapshot, so a cold start shows
  server state while the write replays underneath.

  That is safe but not what users expect after tapping. Doing better means
  storing the optimistic *value* next to the queued write and restoring it on
  hydrate — not replaying the transform, since resume must not re-run
  `onMutate`. Deferred rather than guessed.

## Definition of done

- [x] Durable queue with registry, storage, and resume implemented.
- [x] Test: a write enqueued while offline survives a simulated process death —
      a **fresh queue over the same store** — and is delivered on next launch.
- [x] Test: the record is on disk **before** the first attempt.
- [x] Test: FIFO order preserved within a scope.
- [x] Test: a failing write releases its scope lock, so the queue drains.
- [x] Test: a permanently failing write dead-letters at `maxAttempts` and stops.
- [x] Test: a write past `maxAge` dead-letters instead of replaying.
      **Verified by mutation** — see the expiry note below.
- [x] Test: submitting an unregistered key fails immediately, with a message
      explaining why registration must happen at construction.
- [x] Test: a record whose handler disappeared in an app update dead-letters as
      `Unregistered` rather than being retried forever.
- [x] Test: offline writes wait for connectivity rather than burning attempts.
- [x] Test: `pending` reflects undelivered writes.
- [x] Test: the idempotency id survives a restart unchanged.
- [x] `FileMutationQueueStore`, written atomically, in a separate file from
      the cache.
- [x] Test: the handler receives the idempotency key and attempt number.
      **Found by writing the documentation** — the id existed but could not
      reach the request, making at-least-once delivery unusable in practice.
- [ ] Room-backed `MutationQueueStore` for large queues.
- [ ] Test: resume happens strictly after hydration, end to end.
- [ ] Instrumentation test on a real device using process-death simulation.

### The expiry bug worth remembering

Checking `maxAge` only on entry to delivery was wrong, and the test caught it.
A write that arrives while offline **parks waiting for connectivity** — that is
the whole feature — so the check has to run *again* after the wait. Without the
re-check, a week-old write replays the instant the network returns, which is
precisely the scenario `maxAge` exists to prevent.
