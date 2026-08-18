# Invalidation

## The problem

You just saved something. The list on the previous screen is now wrong.

Invalidation is how a write becomes visible to reads. It marks matching queries
stale and refetches the ones somebody is actually looking at.

## The simplest thing that works

```kotlin
// Everything under "todos" — the list, and any detail queries.
client.invalidateQueries("todos")

// Exactly one entry.
client.invalidateQueries(TodoKey("5"))

// Everything. Spelled out, because it is expensive.
client.invalidateQueries(QueryFilters.All)
```

The usual place is a mutation's `onSettled`:

```kotlin
MutationOptions(
    mutationFn = { api.addTodo(it) },
    onSettled = { _, _, _, _ -> client.invalidateQueries("todos") },
)
```

**Awaiting it keeps the mutation `Pending` until the refetch finishes**, so a
save button stays disabled until the list actually shows the new row, rather
than flashing "done" and updating a moment later.

## There is no no-argument form

`client.invalidateQueries()` does not compile. In web query libraries the no-argument call
invalidates the **entire cache**, while reading exactly like "the thing I just
changed" — a footgun whose cost is invisible in development and expensive on
cellular. Invalidating everything costs four extra characters here and cannot
happen by accident.

## Matching

Matching is a **deep partial match**, not merely a key prefix:

```kotlin
// Matches ["todos"], ["todos", 1], ["todos", {"done": true, "page": 2}]
client.invalidateQueries("todos")

// Matches entries whose key map CONTAINS done=true, whatever else it holds
client.invalidateQueries(QueryFilters(keyPrefix = listOf("todos", mapOf("done" to true))))
```

Narrow further with filters:

```kotlin
client.refetchQueries(
    QueryFilters(
        keyPrefix = listOf("todos"),
        type = QueryType.Active,       // only what is on screen
        stale = true,
        predicate = { it.observerCount > 1 },
    ),
)
```

`predicate` receives an immutable snapshot rather than the live entry, so a
predicate cannot accidentally mutate cache state while answering a question.

## The related operations

| | |
|---|---|
| `invalidateQueries` | mark stale; refetch the active ones |
| `refetchQueries` | refetch regardless of staleness |
| `removeQueries` | drop entries outright — no refetch, no data kept |
| `resetQueries` | back to the initial state |
| `cancelQueries` | abort in-flight requests, without marking them failed |

## What goes wrong

**Inactive queries do not refetch immediately, and should not.** They are marked
stale and refetch when something observes them again. Refetching data nobody is
looking at spends a request to warm a screen the user may never return to.

**Data stays on screen during the refetch.** Invalidation does not evict. The
list keeps rendering while the new data loads — that is the whole point of
stale-while-revalidate. If you want the data *gone*, that is `removeQueries`.

**Invalidating twice while a refetch is in flight fetches once.** Invalidation is
idempotent for as long as the refetch is running. Two *sequential* invalidations,
where the first has settled, legitimately fetch twice.

**Disabled and `Static` queries ignore it.** A query with `enabled = false` has
opted out of automatic fetching entirely, and `StaleTime.Static` means "this
cannot change while the app runs" — invalidating it would contradict what you
declared.

**A failed refetch does not escape.** If an invalidated query's refetch fails,
the error lands in *that query's* state; it does not propagate out of
`invalidateQueries` and surface at an unrelated call site.

## Related

- [Mutations](mutations.md) — where invalidation is usually called from
- [Caching](caching.md) — staleness, and what refetching means
- [Query state](query-state.md) — what a refetching query looks like
