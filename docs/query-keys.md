# Query keys

## The problem

A cache needs to know when two requests are for the same thing. That identity —
the query key — decides what gets shared, what gets invalidated together, and
what gets stored.

Most libraries make keys strings or arrays, which works but loses two things:
the compiler cannot tell you what a key produces, and nothing stops you
forgetting to include a value the query actually depends on.

## The simplest thing that works

```kotlin
data class TodoKey(val id: String) : QueryKey<Todo> {
    override val parts get() = listOf("todo", id)
}

data class TodoListKey(val done: Boolean) : QueryKey<List<Todo>> {
    override val parts get() = listOf("todos", mapOf("done" to done))
}
```

A `data class` gives you structural equality for free, which is what the cache
uses for identity. The type parameter carries the data type through the whole
API:

```kotlin
val todo: Todo? = client.getQueryData(TodoKey("5"))          // typed, no cast
client.setQueryData(TodoKey("5")) { it?.copy(done = true) }  // lambda param is Todo?
```

## Why the missing-dependency bug cannot happen

The classic cache bug is a query function reading a variable that is not in the
key, so two different results collide in one entry. TanStack ships an ESLint
rule to catch it.

Here it does not compile. The key's constructor parameters are the only things
in scope to build `parts` from, so a forgotten dependency is a forgotten
constructor parameter — and the call site that builds the key won't compile
without it.

## `parts` and matching

`parts` is the array-shaped view used for filtering and persistence. Matching is
a **deep partial match**:

```kotlin
client.invalidateQueries("todos")   // matches ["todos"], ["todos", 1], …
```

- Lists match positionally, and the filter may be shorter.
- **Maps match on subset, recursively** — a filter of `{"done": true}` matches an
  entry keyed `{"done": true, "page": 2}`.
- Null-valued map entries are treated as absent.

Order matters in lists; it does not in maps.

## What goes wrong

**`parts` must be pure and cheap.** It is a `get()`, evaluated on demand rather
than stored, because keys are constructed constantly while `parts` is only
consulted for filtering and persistence. Do not do work in it.

**Kotlin has one `null` and JavaScript has two.** A null map *value* is treated
as absent, so `mapOf("filter" to null)` matches an entry with no `"filter"` at
all — which is what the idiomatic `mapOf("filter" to filterOrNull)` means. Nulls
inside a *list* are preserved, because position is significant there.

**Enums are encoded by `name`, never `ordinal`.** If they were positional,
reordering enum constants in a later release would silently repoint every
persisted key at different data — a corruption that only appears after an update
ships.

**Only some types are encodable.** For persistence, `parts` may contain
`String`, `Boolean`, numbers, enums, `List` and `Map`. Anything else throws when
the key is used, with a message naming the offending path — rather than failing
silently at persist time and leaving you with a cache that never survives a cold
start.

**Two keys of different classes with equal `parts` are different keys.** Identity
is the data class's `equals`, not the encoded string. The encoding is only for
disk.

## Related

- [Queries](queries.md) — using a key
- [Invalidation](invalidation.md) — matching several keys at once
- [Persistence](persistence.md) — where the encoding matters
