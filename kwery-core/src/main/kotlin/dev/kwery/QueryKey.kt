package dev.kwery

/**
 * The identity of a cache entry.
 *
 * [T] is the type of data the query produces. It is a phantom type parameter —
 * no member uses it — and exists so that [QueryKey] carries its data type
 * through the API, making `getQueryData`, `setQueryData` and `select`
 * type-safe without casts.
 *
 * Implement as a `data class`, which supplies the structural `equals`/`hashCode`
 * used for in-memory cache identity:
 *
 * ```kotlin
 * data class TodoKey(val id: String) : QueryKey<Todo> {
 *     override val parts get() = listOf("todo", id)
 * }
 * ```
 *
 * [T] is deliberately **invariant**. Marking it `out` would let the compiler
 * widen `QueryKey<Todo>` to `QueryKey<Any>` when inferring a type from another
 * argument, so `setQueryData(TodoKey("1"), "not a todo")` would compile —
 * defeating the point of typed keys.
 */
public interface QueryKey<T> {
    /**
     * Array-shaped view of this key, used for hashing, partial matching and
     * persistence. Order is significant.
     *
     * Implementations must be **pure and cheap**. This is declared as a
     * property so it can be computed on demand: keys are constructed on every
     * query call, while `parts` is consulted only when something actually needs
     * the array-shaped view. In-memory lookup uses the key's own
     * `equals`/`hashCode` and never touches this.
     *
     * Supported element types are those the installed [QueryKeyCodec] accepts;
     * the default handles `String`, `Boolean`, numbers, enums, `List` and `Map`,
     * nested arbitrarily.
     */
    public val parts: List<Any?>
}

/**
 * Matches queries whose key *partially* contains [parts].
 *
 * This is the filter form used by invalidation and friends:
 *
 * ```kotlin
 * client.invalidateQueries(prefixOf("todos"))               // every todo query
 * client.invalidateQueries(prefixOf("todos", mapOf("done" to true)))
 * ```
 *
 * Matching is a deep partial match, not merely a list prefix — see
 * [partialMatchKey].
 */
public fun prefixOf(vararg parts: Any?): QueryFilters =
    QueryFilters(keyPrefix = parts.toList())

/**
 * True when [full] deeply contains [partial].
 *
 * The semantics are ported from TanStack Query's `partialMatchKey`
 * (`.reference/tanstack-query/packages/query-core/src/__tests__/utils.test.tsx`):
 *
 * - Lists match positionally, and [partial] may be shorter:
 *   `[1, 2, 3]` contains `[1, 2]`.
 * - Maps match on subset, recursively: a filter of `{"done": true}` matches an
 *   entry keyed `{"done": true, "page": 1}`.
 * - Null-valued map entries in [partial] are treated as absent, so
 *   `mapOf("filter" to null)` matches a map without a `"filter"` entry at all.
 * - Anything else is compared with `==`.
 */
public fun partialMatchKey(full: List<Any?>, partial: List<Any?>): Boolean =
    partialMatchValue(full, partial)

private fun partialMatchValue(full: Any?, partial: Any?): Boolean = when {
    full == null || partial == null -> full == partial

    full is Map<*, *> && partial is Map<*, *> ->
        // Null values in the filter mean "absent", matching JS `undefined`.
        partial.entries
            .filter { it.value != null }
            .all { (key, value) -> partialMatchValue(full[key], value) }

    full is List<*> && partial is List<*> ->
        partial.size <= full.size &&
            partial.indices.all { partialMatchValue(full[it], partial[it]) }

    else -> full == partial
}
