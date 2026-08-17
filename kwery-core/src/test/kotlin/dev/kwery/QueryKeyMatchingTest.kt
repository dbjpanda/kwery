package dev.kwery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ported from TanStack Query's `partialMatchKey` suite
 * (`.reference/tanstack-query/packages/query-core/src/__tests__/utils.test.tsx`).
 *
 * JS objects become `Map`, JS `undefined` object properties become Kotlin
 * `null` map values. Case names follow the originals so the correspondence
 * stays checkable against the vendored source.
 */
class QueryKeyMatchingTest {

    @Test
    fun `should return true if a includes b`() {
        val a = listOf(mapOf("a" to mapOf("b" to "b"), "c" to "c", "d" to listOf(mapOf("d" to "d"))))
        val b = listOf(mapOf("a" to mapOf("b" to "b"), "c" to "c", "d" to emptyList<Any?>()))
        assertTrue(partialMatchKey(a, b))
    }

    @Test
    fun `should return false if a does not include b`() {
        val a = listOf(mapOf("a" to mapOf("b" to "b"), "c" to "c", "d" to emptyList<Any?>()))
        val b = listOf(mapOf("a" to mapOf("b" to "b"), "c" to "c", "d" to listOf(mapOf("d" to "d"))))
        assertFalse(partialMatchKey(a, b))
    }

    @Test
    fun `should return true if list a includes list b`() {
        assertTrue(partialMatchKey(listOf(1, 2, 3), listOf(1, 2)))
    }

    @Test
    fun `should return false if a is null and b is not`() {
        val a = listOf(null)
        val b = listOf(mapOf("a" to mapOf("b" to "b")))
        assertFalse(partialMatchKey(a, b))
    }

    @Test
    fun `should return false if a contains null and b is not`() {
        val a = listOf(mapOf("a" to null, "c" to "c"))
        val b = listOf(mapOf("a" to mapOf("b" to "b"), "c" to "c"))
        assertFalse(partialMatchKey(a, b))
    }

    @Test
    fun `should return false if b is null and a is not`() {
        val a = listOf(mapOf("a" to mapOf("b" to "b")))
        val b = listOf(null)
        assertFalse(partialMatchKey(a, b))
    }

    @Test
    fun `should return false if b contains null and a is not`() {
        val a = listOf(mapOf("a" to mapOf("b" to "b"), "c" to "c", "d" to emptyList<Any?>()))
        val b = listOf(mapOf("a" to null, "c" to "c", "d" to listOf(mapOf("d" to "d"))))
        assertFalse(partialMatchKey(a, b))
    }

    @Test
    fun `should treat null map values as matching missing properties`() {
        val withNull = listOf("todos", mapOf("filters" to null))
        val withoutProperty = listOf("todos", emptyMap<String, Any?>())

        assertTrue(partialMatchKey(withoutProperty, withNull))
        assertTrue(partialMatchKey(withNull, withoutProperty))
    }

    // ---- Kwery-specific: the documented filter behaviour ----------------

    @Test
    fun `prefix matches longer keys but not a different first part`() {
        assertTrue(partialMatchKey(listOf("todos"), listOf("todos")))
        assertTrue(partialMatchKey(listOf("todos", 1), listOf("todos")))
        assertTrue(partialMatchKey(listOf("todos", mapOf("done" to true)), listOf("todos")))
        assertFalse(partialMatchKey(listOf("todo", 1), listOf("todos")))
    }

    @Test
    fun `map filter matches a superset entry`() {
        val entry = listOf("todos", mapOf("done" to true, "page" to 1))
        assertTrue(partialMatchKey(entry, listOf("todos", mapOf("done" to true))))
        assertFalse(partialMatchKey(entry, listOf("todos", mapOf("done" to false))))
    }

    @Test
    fun `a filter longer than the key never matches`() {
        assertFalse(partialMatchKey(listOf("todos"), listOf("todos", 1)))
    }

    // ---- QueryFilters wiring -------------------------------------------

    private data class TodoKey(val id: String) : QueryKey<String> {
        override val parts get() = listOf("todo", id)
    }

    private fun snapshot(
        key: QueryKey<*>,
        observers: Int = 0,
        stale: Boolean = false,
        fetchStatus: FetchStatus = FetchStatus.Idle,
    ) = QueryEntrySnapshot(
        key = key,
        status = QueryStatus.Success,
        fetchStatus = fetchStatus,
        dataUpdatedAt = 0L,
        isStale = stale,
        isInvalidated = false,
        observerCount = observers,
    )

    @Test
    fun `QueryFilters All matches everything`() {
        assertTrue(QueryFilters.All.matches(snapshot(TodoKey("1"))))
    }

    @Test
    fun `exact key matches only that entry`() {
        val filters = QueryFilters(exactKey = TodoKey("1"))
        assertTrue(filters.matches(snapshot(TodoKey("1"))))
        assertFalse(filters.matches(snapshot(TodoKey("2"))))
    }

    @Test
    fun `prefixOf matches every entry under the prefix`() {
        val filters = prefixOf("todo")
        assertTrue(filters.matches(snapshot(TodoKey("1"))))
        assertTrue(filters.matches(snapshot(TodoKey("2"))))
    }

    @Test
    fun `type filter distinguishes active from inactive`() {
        val active = QueryFilters(type = QueryType.Active)
        val inactive = QueryFilters(type = QueryType.Inactive)

        assertTrue(active.matches(snapshot(TodoKey("1"), observers = 1)))
        assertFalse(active.matches(snapshot(TodoKey("1"), observers = 0)))
        assertTrue(inactive.matches(snapshot(TodoKey("1"), observers = 0)))
        assertFalse(inactive.matches(snapshot(TodoKey("1"), observers = 1)))
    }

    @Test
    fun `criteria combine conjunctively`() {
        val filters = QueryFilters(
            keyPrefix = listOf("todo"),
            type = QueryType.Active,
            stale = true,
        )
        assertTrue(filters.matches(snapshot(TodoKey("1"), observers = 1, stale = true)))
        assertFalse(filters.matches(snapshot(TodoKey("1"), observers = 1, stale = false)))
        assertFalse(filters.matches(snapshot(TodoKey("1"), observers = 0, stale = true)))
    }

    @Test
    fun `predicate is applied last`() {
        val filters = QueryFilters(
            keyPrefix = listOf("todo"),
            predicate = { it.key is TodoKey && (it.key as TodoKey).id == "7" },
        )
        assertTrue(filters.matches(snapshot(TodoKey("7"))))
        assertFalse(filters.matches(snapshot(TodoKey("8"))))
    }

    @Test
    fun `data class keys are structurally equal`() {
        assertEquals(TodoKey("1"), TodoKey("1"))
        assertEquals(TodoKey("1").hashCode(), TodoKey("1").hashCode())
    }
}
