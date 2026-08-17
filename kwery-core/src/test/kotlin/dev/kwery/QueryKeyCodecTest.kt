package dev.kwery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Ported from TanStack Query's `hashKey` suite
 * (`.reference/tanstack-query/packages/query-core/src/__tests__/utils.test.tsx`),
 * plus the persistence-stability cases that JavaScript never had to worry about.
 */
class QueryKeyCodecTest {

    @Test
    fun `should encode primitives correctly`() {
        assertEquals("""["test"]""", encodeKey(listOf("test")))
        assertEquals("[123]", encodeKey(listOf(123)))
        assertEquals("[null]", encodeKey(listOf(null)))
        assertEquals("[true]", encodeKey(listOf(true)))
    }

    @Test
    fun `should encode maps with sorted keys consistently`() {
        val one = listOf(mapOf("b" to 2, "a" to 1))
        val two = listOf(mapOf("a" to 1, "b" to 2))

        assertEquals(encodeKey(one), encodeKey(two))
        assertEquals("""[{"a":1,"b":2}]""", encodeKey(one))
    }

    @Test
    fun `should encode lists consistently`() {
        val one = listOf(mapOf("b" to 2, "a" to 1), "test", 123)
        val two = listOf(mapOf("a" to 1, "b" to 2), "test", 123)

        assertEquals(encodeKey(one), encodeKey(two))
    }

    @Test
    fun `should handle nested maps with sorted keys`() {
        val one = listOf(mapOf("a" to mapOf("d" to 4, "c" to 3), "b" to 2))
        val two = listOf(mapOf("b" to 2, "a" to mapOf("c" to 3, "d" to 4)))

        assertEquals(encodeKey(one), encodeKey(two))
    }

    @Test
    fun `should encode null map values the same as missing properties`() {
        val withNull = listOf("todos", mapOf("filters" to null))
        val withoutProperty = listOf("todos", emptyMap<String, Any?>())

        assertEquals(encodeKey(withNull), encodeKey(withoutProperty))
    }

    @Test
    fun `list order is significant`() {
        assertNotEquals(encodeKey(listOf("a", "b")), encodeKey(listOf("b", "a")))
    }

    @Test
    fun `nulls inside a list are preserved`() {
        // Unlike null map values, list position is significant.
        assertNotEquals(encodeKey(listOf("a", null, "b")), encodeKey(listOf("a", "b")))
    }

    // ---- Persistence stability -----------------------------------------

    private enum class StatusV1 { Active, Done }
    private enum class StatusV2 { Done, Active } // same constants, reordered

    @Test
    fun `enums encode by name so reordering constants is safe`() {
        // The regression test for the `ordinal` trap: if enums encoded by
        // position, shipping an app update that reorders constants would
        // silently repoint every persisted key at different data.
        assertEquals(
            encodeKey(listOf("todos", StatusV1.Done)),
            encodeKey(listOf("todos", StatusV2.Done)),
        )
        assertEquals("""["todos","Done"]""", encodeKey(listOf("todos", StatusV1.Done)))
    }

    @Test
    fun `strings with special characters round-trip safely`() {
        val encoded = encodeKey(listOf("""a"b\c""", "line\nbreak", "tab\there"))
        assertTrue(encoded.contains("""\"""))
        assertTrue(encoded.contains("""\\"""))
        assertTrue(encoded.contains("""\n"""))
        assertTrue(encoded.contains("""\t"""))
        // Distinct inputs must not collide through escaping.
        assertNotEquals(encodeKey(listOf("""a"b""")), encodeKey(listOf("""a\"b""")))
    }

    @Test
    fun `distinct keys do not collide`() {
        val encodings = listOf(
            encodeKey(listOf("todos")),
            encodeKey(listOf("todos", 1)),
            encodeKey(listOf("todos", "1")),
            encodeKey(listOf("todo", 1)),
            encodeKey(listOf(listOf("todos"))),
            encodeKey(listOf(mapOf("todos" to 1))),
        )
        assertEquals(encodings.size, encodings.toSet().size, "encodings collided: $encodings")
    }

    // ---- Rejection of unencodable parts ---------------------------------

    private class NotEncodable

    @Test
    fun `unencodable part throws naming the part and its type`() {
        val error = assertFailsWith<IllegalArgumentException> {
            encodeKey(listOf("todos", NotEncodable()))
        }
        assertTrue(error.message!!.contains("parts[1]"), "message should name the path: ${error.message}")
        assertTrue(error.message!!.contains("NotEncodable"), "message should name the type: ${error.message}")
    }

    @Test
    fun `unencodable part nested in a map names the full path`() {
        val error = assertFailsWith<IllegalArgumentException> {
            encodeKey(listOf("todos", mapOf("filter" to NotEncodable())))
        }
        assertTrue(error.message!!.contains("parts[1].filter"), "was: ${error.message}")
    }

    @Test
    fun `a custom codec can extend the supported leaf types`() {
        val codec = QueryKeyCodec { value ->
            if (value is NotEncodable) "\"custom\"" else QueryKeyCodec.Default.encodeLeaf(value)
        }
        assertEquals("""["todos","custom"]""", encodeKey(listOf("todos", NotEncodable()), codec))
    }
}
