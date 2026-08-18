# 11 — Mutations

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | **gate 2 complete** — reconciled against the vendored suite |
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
| `isMutating` (global count) | yes | `QueryClient.isMutating` | done |
| Mutation *filters* (by key / predicate) | yes | the `Mutation` object is the handle | divergent (structural) |
| Observe others' mutations (`useMutationState`) | yes | `client.mutationStates(filters)` | planned |
| Throwing `onSettled` promotes success → error | yes | yes | done |
| Throwing `onError` loses the original error | yes — unhandled rejection | **no** — original stays primary, callback attached as suppressed | divergent (better) |
| Cache-level (global) callbacks | yes, with an extra `mutation` arg | **not ported** — see below | divergent (gap) |
| Mutation cache with `gcTime` | yes | **not ported** — see below | divergent (gap) |
| `onSettled` uses `null` for error, `undefined` for data | yes | normalised: `null` for both | divergent (Kotlin) |
| Call-level callbacks need a live subscriber to fire | yes | n/a — no call-level callbacks | divergent (gap) |
| Late-bound options via `setOptions` | yes | n/a — no `setOptions` | divergent (gap) |

### Reconciliation against the vendored suite

Reading `mutations.test.tsx`, `mutationCache.test.tsx` and `mutationObserver.test.tsx`
surfaced behaviour worth recording explicitly:

**Matched, and now covered by tests.** A callback that throws on the *success*
path promotes the whole mutation to `Error` — `onError` runs even though the
write itself succeeded, and `onSettled` is entered a second time. Kwery does
this because `onSuccess`/`onSettled` sit inside the same `try` as the mutation
function, which turned out to be the correct shape.

**Deliberately better.** TanStack routes a throw from `onError` to an
unhandled-rejection channel, where it is easy to lose, and the mutation still
rejects with the original error. Kwery keeps the original error primary *and*
attaches the callback's failure as a **suppressed** exception, so neither is
lost and no separate reporting channel is needed.

**Normalised.** TanStack's `onSettled` receives `null` for "no error" but
`undefined` for "no data" — an asymmetry Kotlin cannot express, since it has a
single `null`. Both are `null` here.

**Two real gaps, both deferred with reasons.** TanStack has a `MutationCache`
holding every mutation, which provides (a) *global* callbacks with an extra
`mutation` argument, and (b) garbage collection of settled mutations. Kwery's
`client.mutation()` returns a fresh object each call and caches nothing, so
neither exists. Global callbacks are a cross-cutting-concern hook (logging,
error reporting) that is better served in Kotlin by wrapping `mutationFn`;
mutation GC only matters once mutations are retained by key, which is exactly
what [14](14-offline-mutation-queue.md) introduces. Both are revisited there.

**Note for [14](14-offline-mutation-queue.md).** Resuming a paused mutation
must **not** re-run `onMutate` — TanStack asserts this explicitly
(`expect(onMutate).not.toHaveBeenCalled()`). An optimistic update was already
applied when the mutation was first submitted; applying it twice on resume
would double it.

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

### `isMutating`: a queued write is still outstanding

`QueryClient.isMutating` counts a mutation from submission until its callbacks
finish — **including time spent queued** behind another mutation in the same
`MutationScope`.

The alternative, counting only once the scope lock is held, is the more obvious
implementation and is wrong in a way that shows up on screen: a user submitting
two writes back to back would see the save button re-enable in the gap between
them. A queued write has not happened yet, but it is going to.

The decrement lives in a `finally` that does not suspend, so it survives
cancellation — a suspending cleanup would not run, and a leaked count means a
spinner that never stops.

TanStack's mutation *filters* (`useIsMutating({ mutationKey })`) remain
unimplemented; Kwery mutations have no key to filter on, which is a design
question rather than an oversight.

### Scope locks are held weakly

`QueryClient` keeps one `Mutex` per `MutationScope.id`. The obvious
implementation — a `mutableMapOf<String, Mutex>` with `getOrPut` — grows for the
life of the process with every distinct scope id ever used, and never shrinks.

That is fine for the ids the KDoc suggests (`"uploads"`, a handful of
constants). It is unbounded for per-entity scopes such as `"todo-$id"`, which
are a natural way to serialise edits to a single item. An Android process lives
for days; this is the same argument that put `maxEntries` on the query cache.

Nothing misbehaves when it grows, which is why it survived: every mutation still
serialises correctly and every existing test passed. Catching it needed a test
that **counts** rather than one that checks a result.

The map now holds weak references. That is not merely a size trick — it is the
correct lifetime. The lock matters exactly while some `Mutation` holding it is
alive, and a mutation in flight is strongly reachable from its own running
coroutine, so a lock can never be collected out from under one. Dead keys are
pruned on insert, which only runs when the map is about to grow and never on the
hot path of an existing scope.

`OfflineQueue.scopeLocks` had the identical shape and the identical fix.

**Verified by mutation** from both directions: making the values strong again
fails the accumulation test, and never reusing an existing lock fails three
serialisation tests.

## Definition of done

- [x] `Mutation`, `MutationState`, `MutationOptions` implemented.
- [x] `isMutating: StateFlow<Int>` on `QueryClient`, and `rememberIsMutating`
      in `kwery-compose`.
- [x] Test: mutations sharing a scope share one lock; distinct scopes held at
      once each get one; locks for scopes no longer in use do not accumulate.
      **All verified by mutation.**
- [x] Test: counts concurrent mutations and returns to zero.
- [x] Test: a mutation queued behind its scope still counts.
      **Verified by mutation** — counting only after the lock fails it.
- [x] Test: failed and cancelled mutations both decrement.
      **Verified by mutation** — moving the decrement off the `finally` fails both.
- [ ] ~~Mutation filters (by key or predicate).~~ **Not built — a structural
      divergence, and tested as one.** TanStack needs
      `useIsMutating({ mutationKey })` because its hooks hand back a fresh
      object each render, leaving nothing stable to hold; the filter is how you
      find your mutation again. In Kwery the `Mutation` *is* the handle, so
      "is this one saving?" is `mutation.state.value.status`, typed and local,
      rather than a query against a registry. Two mutations in flight are told
      apart by holding them, which the tests demonstrate. `isMutating` remains
      what it is useful for: a global indicator.
- [x] Test: callbacks fire in documented order and each is awaited.
- [x] Test: `onMutate` result reaches `onError` and `onSettled`, typed.
- [x] Test: mutations do not retry by default; `retry` opts in.
- [x] Test: two mutations sharing a scope run serially, second reports `isPaused`.
      **Verified by mutation**: without the lock, ordering becomes
      `[start:a, start:b, start:c, end:a, …]`.
- [x] Test: mutations in different scopes run concurrently.
- [x] Test: a **failing** scoped mutation still releases the lock. TanStack has
      no test for this; the gap was found while reading their suite.
- [x] Test: `mutateAwait` throws the original exception, not a wrapper.
- [x] Test: `reset()` returns state to `Idle`.
- [x] Test: `onMutate` runs before the mutation waits for its scope turn, so an
      optimistic update is visible on tap rather than after the queue drains.
- [x] Test: offline mutations pause rather than fail, and resume on reconnect.
- [x] Test: a throwing `onSettled` on the success path promotes to `Error` and
      re-enters `onSettled` (ported from TanStack's cascade tests).
- [x] Test: a throwing `onError` does not replace the original failure.
