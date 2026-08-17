package dev.kwery

/**
 * Encodes a [QueryKey]'s `parts` to a canonical string.
 *
 * In-memory identity uses the key's own `equals`/`hashCode` and never needs
 * this. Encoding exists for **persistence**, where an entry must be
 * identifiable across process restarts, app updates and R8 obfuscation.
 *
 * Structure — `List` and `Map` — is handled by [encodeKey] itself. A codec is
 * only asked about *leaf* values, so implementations stay small.
 *
 * `kwery-core` ships [Default], covering `String`, `Boolean`, numbers and
 * enums. `kwery-persist` supplies a richer codec that also handles
 * `@Serializable` types, which is why core needs no serialization dependency.
 */
public fun interface QueryKeyCodec {

    /**
     * Encode [value] as a JSON literal, or return `null` if this codec does not
     * handle that type — which causes [encodeKey] to reject the key.
     */
    public fun encodeLeaf(value: Any): String?

    public companion object {
        /** Handles `String`, `Boolean`, numbers and enums. */
        public val Default: QueryKeyCodec = QueryKeyCodec { value ->
            when (value) {
                is String -> jsonString(value)
                is Boolean -> value.toString()
                // Enums encode by `name`, never `ordinal`: reordering constants
                // in a later app version must not silently repoint every
                // persisted key at different data.
                is Enum<*> -> jsonString(value.name)
                is Int, is Long, is Short, is Byte -> value.toString()
                is Double, is Float -> value.toString()
                else -> null
            }
        }
    }
}

/**
 * Encode [parts] to a canonical string, stable across processes and app
 * versions.
 *
 * Rules, matching TanStack Query's `hashKey`:
 *
 * - Lists preserve order.
 * - Map keys are **sorted**, so `{"b" to 2, "a" to 1}` and `{"a" to 1, "b" to 2}`
 *   encode identically, at any nesting depth.
 * - Null-valued map entries are **dropped**, so `{"filter" to null}` encodes
 *   identically to `{}`.
 *
 * That last rule is the one place Kotlin forces a choice JavaScript did not
 * have to make. JS distinguishes `null` from `undefined`, and TanStack drops
 * only `undefined`. Kotlin has a single `null`, and its idiomatic meaning in
 * `mapOf("filter" to filterOrNull)` is "absent" — so `null` is mapped to
 * `undefined` semantics. Nulls inside a **list** are preserved, because
 * position is significant there.
 *
 * @throws IllegalArgumentException if any part is not encodable by [codec].
 * This is deliberately loud: a key that silently fails to encode would produce
 * a cache that never survives a cold start, with no visible symptom until a
 * user reports it.
 */
public fun encodeKey(
    parts: List<Any?>,
    codec: QueryKeyCodec = QueryKeyCodec.Default,
): String = buildString { encodeValue(parts, codec, this, path = "parts") }

private fun encodeValue(
    value: Any?,
    codec: QueryKeyCodec,
    out: StringBuilder,
    path: String,
) {
    when (value) {
        null -> out.append("null")

        is List<*> -> {
            out.append('[')
            value.forEachIndexed { index, element ->
                if (index > 0) out.append(',')
                encodeValue(element, codec, out, "$path[$index]")
            }
            out.append(']')
        }

        is Map<*, *> -> {
            out.append('{')
            var first = true
            value.entries
                .filter { it.value != null }
                .sortedBy { it.key?.toString() ?: "" }
                .forEach { (key, entryValue) ->
                    if (!first) out.append(',')
                    first = false
                    out.append(jsonString(key?.toString() ?: "null")).append(':')
                    encodeValue(entryValue, codec, out, "$path.$key")
                }
            out.append('}')
        }

        else -> {
            val encoded = codec.encodeLeaf(value)
                ?: throw IllegalArgumentException(
                    "Query key part at $path is not encodable: " +
                        "${value::class.qualifiedName ?: value::class}. " +
                        "Supported by the default codec: String, Boolean, numbers, enums, " +
                        "List and Map. Flatten the value, or install a QueryKeyCodec that " +
                        "handles it.",
                )
            out.append(encoded)
        }
    }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
