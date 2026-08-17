# 14 — Offline Mutation Queue

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | planned — **highest-risk feature in v1** |
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
val client = QueryClient {
    mutations {
        register(AddTodoKey) { input: AddTodoInput -> api.addTodo(input) }
        register(DeleteTodoKey) { id: String -> api.deleteTodo(id) }
    }
}
```

Registration is required for any mutation declared as durable. Kwery **fails at
client construction** if a durable mutation key has no registered function,
rather than at resume time in the field:

```kotlin
client.mutation(AddTodoKey, durable = true)   // requires prior registration
```

This converts TanStack's most confusing runtime error into a startup failure a
developer sees on their first run.

### Queue semantics

Decisions this feature must make explicitly, because getting them wrong silently
loses or duplicates user data:

- **Ordering.** FIFO within a `MutationScope`; scopes proceed independently.
  Global FIFO is simpler but serialises unrelated writes needlessly.
- **Durability.** Variables are serialised via kotlinx-serialization; the queue
  is written **before** the first attempt, not after failure. A process death
  between "user tapped save" and "first network attempt" must not lose the write.
- **At-least-once.** Delivery is at-least-once, not exactly-once. A mutation may
  have reached the server before the process died. Kwery **cannot** solve this
  alone — it will document that non-idempotent mutations should carry a
  client-generated idempotency key, and provide `MutationState.idempotencyKey`
  (a stable UUID minted at enqueue) to make that easy.
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
| Missing fn detected | runtime, on resume | **client construction** | divergent (better) |
| Survives process death | no (tab-scoped) | **yes** | divergent (better) |
| Queue written before first attempt | no | yes | divergent (better) |
| Idempotency key | no | `MutationState.idempotencyKey` | divergent (addition) |
| Dead-letter for poison mutations | no | yes | divergent (addition) |
| Queue entry expiry | no | `maxAge`, default 7 days | divergent (addition) |
| Scoped serial execution | yes | yes | planned |

## Open questions

- **OQ-1.** Where does the queue live — in the same persisted blob as the query
  cache ([15](15-persistence.md)) or its own store? Separate is better: the
  cache is disposable and can be dropped wholesale on a `buster` change, but the
  **queue must never be dropped by a cache version bump**. Strongly leaning
  separate stores. This needs to be settled before either feature is built.
- **OQ-2.** What is the right dead-letter default — attempt count, elapsed time,
  or both? Leaning both, with time dominant.
- **OQ-3.** Should the queue be exposed as an observable so apps can render a
  "3 changes pending" indicator? Likely yes — it is a small addition and a
  visible product win.
- **OQ-4.** Resolve jointly with [12](12-optimistic-updates.md) OQ-3: are
  optimistic writes persisted alongside the queued mutation, so a cold start
  shows the user's pending edit? Users expect yes; it makes hydration
  substantially more complex.

## Definition of done

- [ ] Durable queue with registry, persistence, and resume implemented.
- [ ] Test: mutation enqueued while offline survives simulated process death and
      executes on next launch once online.
- [ ] Test: queue is written **before** the first attempt (kill between enqueue
      and attempt loses nothing).
- [ ] Test: FIFO order preserved within a scope across a restart.
- [ ] Test: independent scopes do not block each other.
- [ ] Test: a permanently failing mutation dead-letters and unblocks its scope.
- [ ] Test: entries past `maxAge` dead-letter instead of replaying.
- [ ] Test: client construction throws when a durable key lacks a registered fn.
- [ ] Test: resume happens strictly after hydration.
- [ ] Instrumentation test on a real device using process-death simulation, not
      only a JVM fake.
