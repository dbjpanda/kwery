# 11 — Mutations

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | gate 2 in progress — implemented and tested; reconciling against the vendored suite |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/mutations.md`](../../.reference/tanstack-query/docs/framework/react/guides/mutations.md), [`guides/invalidations-from-mutations.md`](../../.reference/tanstack-query/docs/framework/react/guides/invalidations-from-mutations.md) |
| **Blocks** | 12 Optimistic updates, 14 Offline queue |

Writes. Without these Kwery is a read-only cache and only half the library.

## TanStack behaviour

`useMutation({mutationFn})` returns `mutate` (fire and forget) and `mutateAsync`
(returns a promise). Status is `idle | pending | error | success` — note the
extra `idle` compared to a query, because a mutation has not run until invoked.

Lifecycle callbacks, in order, each awaited before the next:

- `onMutate(variables, context)` → its return value is passed to `onError` and
  `onSettled` as `onMutateResult`. This is the rollback channel.
- `onSuccess(data, variables, onMutateResult, context)`
- `onError(error, variables, onMutateResult, context)`
- `onSettled(data, error, variables, onMutateResult, context)`

Callbacks may also be passed per-call to `mutate`. Those run **after** the
mutation-level ones, fire only once, and — critically — **do not run if the
component unmounts first**. With consecutive `mutate` calls, only the last
call's per-call callbacks fire, because the observer is resubscribed each time.
This is a genuine footgun that has cost real teams real bugs.

Other behaviours:

- Mutations do **not** retry by default (`retry: 0`).
- `reset()` clears `error`/`data`.
- **Mutation scopes**: mutations sharing a `scope.id` run **serially**; a
  mutation whose scope is busy starts in `isPaused: true` and queues.
- `mutationKey` + `setMutationDefaults(key, options)` registers a mutation
  function globally — the prerequisite for resuming persisted mutations, since
  functions cannot be serialised.
- Offline failures are retried **in the same order** on reconnect.

## Kwery design

```kotlin
interface MutationKey<V, R> { val parts: List<Any?> }

data class MutationState<V, R>(
    val status: MutationStatus = MutationStatus.Idle,
    val data: R? = null,
    val error: Throwable? = null,
    val variables: V? = null,
    val submittedAt: Long? = null,
    val isPaused: Boolean = false,
    val failureCount: Int = 0,
)

enum class MutationStatus { Idle, Pending, Error, Success }
```

```kotlin
class Mutation<V, R> internal constructor(...) {
    val state: StateFlow<MutationState<V, R>>
    fun mutate(variables: V)                       // fire and forget
    suspend fun mutateAwait(variables: V): R       // throws on failure
    fun reset()
}
```

`mutateAwait` rather than `mutateAsync`: in Kotlin the suspending variant *is*
the natural one, and `Async` would misleadingly suggest it does not block the
caller's coroutine.

### Lifecycle callbacks with a typed rollback channel

TanStack's `onMutateResult` is `unknown` and every consumer casts it. Kwery
parameterises it:

```kotlin
class MutationOptions<V, R, C>(              // C = the onMutate context type
    val mutationFn: suspend (V) -> R,
    val onMutate: (suspend (V) -> C)? = null,
    val onSuccess: (suspend (R, V, C?) -> Unit)? = null,
    val onError: (suspend (Throwable, V, C?) -> Unit)? = null,
    val onSettled: (suspend (R?, Throwable?, V, C?) -> Unit)? = null,
    val retry: RetryPolicy = RetryPolicy.ForMutations,
    val scope: MutationScope? = null,
)
```

The third type parameter is the cost; type-safe rollback with no casts is the
benefit. Given that rollback correctness is exactly where optimistic updates go
wrong, the trade is worth it. A `MutationOptions<V, R, Unit>` alias keeps the
common no-context case clean.

### Per-call callbacks

Kwery **omits** TanStack's per-call callback behaviour. Its "only the last one
fires, and only if still mounted" semantics are surprising and have no good
analogue outside React's observer resubscription model. The Kotlin equivalent is
simply to use `mutateAwait` and write the follow-up code after it — which is
clearer, always runs, and composes:

```kotlin
val todo = mutation.mutateAwait(input)   // throws on failure
analytics.track("todo_created", todo.id)
```

This is a deliberate parity gap, recorded as such.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `mutate` fire-and-forget | yes | yes | planned |
| `mutateAsync` | yes | `mutateAwait` | planned |
| 4-state status incl. `idle` | yes | yes | planned |
| `onMutate` / `onSuccess` / `onError` / `onSettled` | yes | yes | planned |
| Callbacks awaited in order | yes | yes | planned |
| Typed `onMutate` context | `unknown` | type parameter `C` | divergent (better) |
| Per-call callbacks | yes | **omitted** — use `mutateAwait` | **divergent (gap)** |
| No retry by default | yes | `RetryPolicy.ForMutations` | planned |
| `reset()` | yes | yes | planned |
| `variables` exposed during flight | yes | yes | planned |
| `submittedAt` | yes | yes | planned |
| Mutation scopes run serially | yes | `MutationScope` | planned |
| `setMutationDefaults` | yes | see [14](14-offline-mutation-queue.md) | planned |
| `isMutating` / mutation filters | yes | yes | planned |
| Observe others' mutations (`useMutationState`) | yes | `client.mutationStates(filters)` | planned |

## Open questions

- **OQ-1.** ~~Is the third type parameter `C` too costly?~~ **Closed: keep it,
  but only on `MutationOptions`.** The question assumed `C` had to appear on
  `Mutation` too, and it does not — nothing on a mutation's observable surface
  exposes the rollback context.

  So `MutationOptions<V, R, C>` is fully typed, giving the user a checked
  rollback snapshot in `onError`/`onSettled` with no cast at the point that
  matters, while `Mutation<V, R>` erases it. The single unchecked cast is
  confined to one line inside `Mutation.execute`, and it is sound because the
  context value came from that same options object.
- **OQ-2.** ~~Reconsider per-call callbacks for the fire-and-forget path?~~
  **Closed: no.** `mutate` returns its `Job`, so a caller who wants to react can
  `mutate(v).join()` or observe `state`. Adding a second callback channel to
  cover a case already served by two mechanisms is API surface for its own sake.

## Definition of done

- [ ] `Mutation`, `MutationState`, `MutationOptions` implemented.
- [ ] Test: callbacks fire in documented order and each is awaited.
- [ ] Test: `onMutate` result reaches `onError` and `onSettled`, typed.
- [ ] Test: mutations do not retry by default; `retry` opts in.
- [ ] Test: two mutations sharing a scope run serially, second reports `isPaused`.
- [ ] Test: mutations in different scopes run concurrently.
- [ ] Test: `mutateAwait` throws the original exception, not a wrapper.
- [ ] Test: `reset()` returns state to `Idle`.
