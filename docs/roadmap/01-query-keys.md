# 01 — Query Keys

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/query-keys.md`](../../.reference/tanstack-query/docs/framework/react/guides/query-keys.md), [`guides/filters.md`](../../.reference/tanstack-query/docs/framework/react/guides/filters.md) |
| **Blocks** | 04 Caching lifecycle, 08 Invalidation, 15 Persistence |
| **Decision** | AD-3 (hybrid typed keys) |

The query key is the identity of a cache entry. Everything else — deduplication,
invalidation, garbage collection, persistence — is defined in terms of it, which
is why this is the first thing to get right and the most expensive thing to
change later.

## TanStack behaviour

Keys are arrays, serializable by `JSON.stringify`, and unique to the query's data.

- **Array order is significant.** `['todos', status, page]` and
  `['todos', page, status]` are different queries.
- **Object key order is not significant.** `['todos', {status, page}]` and
  `['todos', {page, status}]` hash identically — objects are hashed
  deterministically with sorted keys.
- **`undefined` object values are stripped.** `['todos', {page, status, other: undefined}]`
  equals `['todos', {page, status}]`.
- **Keys act as dependencies.** Any variable the query function reads must appear
  in the key, or two logically distinct results collide in one cache entry. This
  is TanStack's most common user error, which is why they ship an
  `exhaustive-deps` ESLint rule to catch it.
- **Prefix matching drives filters.** `invalidateQueries({queryKey: ['todos']})`
  matches `['todos']`, `['todos', 1]`, and `['todos', {type: 'done'}]`. Passing
  `exact: true` restricts to a single entry.

## Kwery design

TanStack uses arrays because JavaScript offers nothing better. Kotlin does, so
Kwery uses typed keys that still expose an array-shaped view for matching.

```kotlin
/**
 * Identity of a cache entry. [T] is the type of data the query produces,
 * which makes setQueryData / getQueryData / select type-safe.
 */
interface QueryKey<T> {
    /**
     * Array-shaped view used for hashing, prefix matching and persistence.
     * Order is significant. Elements must be canonically encodable —
     * see "Canonical encoding" below.
     */
    val parts: List<Any?>
}
```

Consumers declare keys as data classes, which get structural `equals`/`hashCode`
for free:

```kotlin
data class TodoKey(val id: String) : QueryKey<Todo> {
    override val parts get() = listOf("todo", id)
}

data class TodoListKey(val status: Status, val page: Int) : QueryKey<List<Todo>> {
    override val parts get() = listOf("todos", mapOf("status" to status.name, "page" to page))
}
```

The payoff is that the compiler carries the data type through the whole API:

```kotlin
val todo: Todo? = client.getQueryData(TodoKey("5"))     // typed, no cast
client.setQueryData(TodoKey("5")) { it?.copy(done = true) }  // lambda param is Todo?
```

### Prefix matching without instantiating a key

Typed keys cannot express "every todo query" — you would have to construct a
`TodoKey`, which is a specific entry. Prefix matching therefore takes a raw
parts prefix:

```kotlin
client.invalidateQueries(prefixOf("todos"))          // all todo lists
client.invalidateQueries(TodoKey("5"))               // exactly one entry
client.invalidateQueries(QueryFilters(
    keyPrefix = listOf("todos"),
    type = QueryType.Active,
    stale = true,
))
```

`prefixOf(vararg parts: Any?)` is sugar for `QueryFilters(keyPrefix = parts.toList())`.

### Canonical encoding

Two distinct mechanisms, deliberately separated:

**In memory**, identity is `parts` structural equality. Kotlin's `List` and `Map`
`equals` already give TanStack's semantics for free — including order-insensitive
map comparison — with no hashing step and no string allocation. This is strictly
better than TanStack, which must `JSON.stringify` on every lookup.

**On disk** (feature 15), identity must be a string that is stable across
process restarts, app updates, and R8 obfuscation. `hashCode()` is unsuitable:
it is unstable for enums by ordinal, and collision-prone at cache scale.
`parts` is therefore encoded to a canonical string by `QueryKeyCodec`:

- `String` → quoted, escaped
- `Int`/`Long`/`Double`/`Boolean`/`null` → JSON literal
- `Enum` → its `name` (never `ordinal`, which reorders across versions)
- `List` → JSON array, order preserved
- `Map` → JSON object with **keys sorted** and null values **stripped**,
  matching TanStack's deterministic hashing
- anything else → `IllegalArgumentException` at registration time, not at
  persist time

Failing loudly on unencodable parts at query registration is the deliberate
choice: a key that silently fails to persist is invisible until a user reports
that their cache never survives a cold start.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Array-shaped keys | `['todos', 1]` | `parts = listOf("todos", 1)` | planned |
| Array order significant | yes | yes | planned |
| Object key order insignificant | yes, sorted hash | yes, `Map` equality + sorted encoding | planned |
| `undefined`/null map values stripped | yes | yes | planned |
| Prefix matching | `queryKey: ['todos']` | `prefixOf("todos")` | planned |
| Exact matching | `exact: true` | pass a concrete `QueryKey` | planned |
| Predicate filter | `predicate: (q) => …` | `QueryFilters(predicate = …)` | planned |
| Compile-time data type on key | no | yes — `QueryKey<T>` | **divergent (better)** |
| Lint rule for missing dependencies | `exhaustive-deps` ESLint | unnecessary — key fields *are* the deps | **divergent (better)** |
| Key factory convention | community package | data classes are already the factory | **divergent (better)** |

## Deliberate divergences

1. **Keys carry their data type.** `QueryKey<T>` removes the unchecked casts
   that `getQueryData`/`setQueryData` require in TanStack. Cost: the untyped
   filter path must use `QueryKey<*>`, and prefix matching is a separate
   `keyPrefix` concept rather than a shorter key.

2. **No exhaustive-deps lint needed.** TanStack's most common bug — a query
   function reading a variable absent from the key — is structurally prevented:
   the key's constructor parameters are the only thing in scope to build `parts`
   from, so a forgotten dependency is a forgotten constructor parameter, which
   does not compile at the call site.

3. **Structural equality in memory, canonical string only on disk.** TanStack
   hashes to a string for every cache lookup because JS lacks deep equality.
   Kwery pays that cost only when persisting.

## Open questions

- **OQ-1.** Should `parts` be a `val` computed once at construction (faster
  repeat lookups, but data class `copy` would produce a stale `parts`) or a
  `get()` computed per access (always correct, allocates per lookup)? Leaning
  toward computed-once via an abstract base class that consumers extend instead
  of implementing the interface directly.
- **OQ-2.** Should Kwery ship a `@QueryKey` KSP processor that generates `parts`
  from constructor parameters, removing the boilerplate entirely? Attractive,
  but adds a KSP dependency to a library whose selling point is being small.
  Defer to post-v1 and measure whether the boilerplate actually bothers users.
- **OQ-3.** Do we allow `@Serializable` data classes directly inside `parts`,
  encoding via kotlinx-serialization? It removes the "flatten your filter object
  into a map" chore, at the cost of a hard kotlinx-serialization dependency in
  `kwery-core`. Alternative: keep core dependency-free and put it in an optional
  `kwery-key-serialization` artifact.

## Definition of done

- [ ] `QueryKey<T>`, `QueryFilters`, `prefixOf` implemented in `kwery-core`.
- [ ] Structural equality tests mirroring the TanStack doc examples exactly:
      array order matters; map key order does not; null map values stripped.
- [ ] Prefix matching tests: `prefixOf("todos")` matches `["todos"]`,
      `["todos", 1]`, `["todos", {...}]`; does not match `["todo", 1]`.
- [ ] `QueryKeyCodec` round-trip tests for every supported part type.
- [ ] Enum encoding proven stable when enum constants are reordered — the
      regression test for the `ordinal` trap.
- [ ] Unencodable part throws at registration with a message naming the
      offending part and its type.
- [ ] R8-shrunk instrumentation test proving canonical strings survive
      obfuscation.
