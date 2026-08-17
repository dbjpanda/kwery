# Mutations

## The problem

Reads and writes need different machinery. A read can be retried freely, run
whenever a screen appears, and be deduplicated against an identical read. A
write can do none of those safely — retrying a payment is not the same as
retrying a fetch.

A mutation is a write with a lifecycle you can observe and hook into: it has not
run yet, it is running, it succeeded, it failed.

## The simplest thing that works

```kotlin
val addTodo = client.mutation(
    MutationOptions<String, Todo, Unit>(
        mutationFn = { title -> api.addTodo(title) },
        onSettled = { _, _, _, _ -> client.invalidateQueries("todos") },
    ),
)

// Fire and forget — failures show up in state, not as an exception.
addTodo.mutate("Buy milk")

// Or await the result.
val todo = addTodo.mutateAwait("Buy milk")
```

Observe it like any other state:

```kotlin
val state by addTodo.state.collectAsState()

Button(onClick = { addTodo.mutate(title) }, enabled = !state.isPending) {
    Text(if (state.isPending) "Saving…" else "Save")
}
state.error?.let { Text("Failed: ${it.message}") }
```

## Lifecycle

Callbacks run in order, each awaited before the next:

```kotlin
MutationOptions<Input, Result, Snapshot>(
    mutationFn = { input -> api.save(input) },
    onMutate   = { input -> takeSnapshot() },              // before the write
    onSuccess  = { result, input, snapshot -> … },
    onError    = { error, input, snapshot -> … },
    onSettled  = { result, error, input, snapshot -> … },  // always
)
```

The third type parameter is the **rollback channel**: whatever `onMutate`
returns is handed to `onError` and `onSettled`, fully typed. TanStack types this
as `unknown` and every caller casts; here a snapshot cannot be misread.

**The mutation stays `Pending` until `onSettled` finishes.** That is what makes
this work:

```kotlin
onSettled = { _, _, _, _ -> client.invalidateQueries("todos") }
```

The button stays disabled until the list has actually refreshed, instead of
flashing "done" and then visibly updating a moment later.

## Options

| | Default | |
|---|---|---|
| `retry` | **none** | Unlike queries, which retry 3 times |
| `networkMode` | `Online` | Pause rather than fail when offline |
| `scope` | none | Mutations sharing a scope run one at a time |

**Mutations do not retry by default, and queries do.** A retried non-idempotent
write can charge a customer twice. Opt in only for writes that are safe to
repeat:

```kotlin
MutationOptions(mutationFn = …, retry = RetryPolicy.Times(3))
```

**Scopes** serialise writes that must not race:

```kotlin
MutationOptions(mutationFn = …, scope = MutationScope("doc:$docId"))
```

A mutation whose scope is busy reports `isPaused` and waits its turn. Different
scopes run concurrently.

## What goes wrong

**`mutate` does not throw; `mutateAwait` does.** Fire-and-forget failures land
in `state.error` only. If you need to react, either use `mutateAwait` and write
the follow-up code after it, or observe the state — `mutate` returns its `Job`
if you want to join it.

**Kwery has no per-call callbacks.** TanStack lets you pass `onSuccess` to
`mutate()` itself; those fire only for the *last* of several concurrent calls,
and only if the component is still mounted. Those rules model React's observer
resubscription and translate to nothing sensible in Kotlin. Write the follow-up
after `mutateAwait` instead — it always runs, and needs no explanation.

**A callback that throws can fail a successful write.** If `onSuccess` or
`onSettled` throws after the write succeeded, the mutation ends in `Error` and
`onError` runs, because something in the operation genuinely did fail. This
matches TanStack. Keep side effects in callbacks small, and do not let a
logging failure fail a save.

**A throwing `onError` never hides the real failure.** The original error stays
primary and the callback's failure is attached as a **suppressed** exception, so
neither is lost. TanStack routes these to an unhandled-rejection channel, where
they are easy to miss.

**`variables` survive an error, deliberately.** After a failure, `state.variables`
still holds what was submitted, so a retry button needs no extra bookkeeping:

```kotlin
state.error?.let {
    Button(onClick = { addTodo.mutate(state.variables!!) }) { Text("Retry") }
}
```

**Offline mutations pause, they do not fail.** Under the default `networkMode`,
a write with no connectivity reports `isPaused` and waits. That is in-memory
only — it dies with the process. For writes that must survive that, see
[offline writes](offline.md).

## Related

- [Optimistic updates](optimistic-updates.md) — showing a write before it lands
- [Offline writes](offline.md) — writes that survive process death
- [Query state](query-state.md) — the equivalent model for reads
