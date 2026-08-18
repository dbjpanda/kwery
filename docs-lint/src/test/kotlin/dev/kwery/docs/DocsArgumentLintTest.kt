package dev.kwery.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Named arguments in the docs must be parameters that actually exist.
 *
 * This is the rot class [DocsApiLintTest] cannot see. A name that is not there
 * at all it catches; a call with the right *name* but a parameter that was
 * renamed, reordered or never existed it does not. Both of this project's worst
 * documentation errors were of that shape —
 * `prefetchQuery(key, staleTime, fetcher)` lifted from a design sketch when the
 * real signature takes `QueryOptions`, and a `getNextPageParam` example written
 * with one argument when it takes three.
 *
 * Parameter names live only in the Kotlin sources: a JVM `.api` dump does not
 * record them. So this reads the sources, and deliberately checks only calls to
 * declarations Kwery owns — a reader's own `api.todo(id = …)` is not our
 * business.
 */
class DocsArgumentLintTest {

    private val root = File(System.getProperty("kwery.repoRoot"))
    private val docs = File(root, "docs")

    /** Declaration name to the parameter names it accepts. */
    private val parameters: Map<String, Set<String>> by lazy {
        val result = mutableMapOf<String, MutableSet<String>>()
        val declaration = Regex("""public (?:inline )?(?:suspend )?(?:data )?(?:class|fun)\s+(?:<[^>]*>\s+)?(?:[\w.]+\.)?(\w+)\s*(?:<[^>]*>)?\s*\(""")

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }
            .forEach { file ->
                // KDoc sits between parameters in every options class here, and
                // a comment can contain anything — including text that looks
                // like a parameter. Strip comments before parsing structure.
                val text = file.readText()
                    .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("""//.*"""), "")
                declaration.findAll(text).forEach { match ->
                    val name = match.groupValues[1]
                    val open = text.indexOf('(', match.range.last - 1)
                    val params = paramNames(text, open) ?: return@forEach
                    result.getOrPut(name) { mutableSetOf() } += params
                }
            }
        result
    }

    /**
     * Parameter names between [open] and its matching close paren.
     *
     * Depth-tracked rather than regex-matched: default values contain their own
     * parens and commas — `QueryOptions(staleTime = StaleTime.of(5.minutes))` —
     * and a flat match reads those as further parameters.
     */
    private fun paramNames(text: String, open: Int): Set<String>? {
        if (open < 0 || text.getOrNull(open) != '(') return null
        var depth = 0
        var i = open
        val body = StringBuilder()
        while (i < text.length) {
            when (text[i]) {
                '(' -> { depth++; if (depth > 1) body.append(text[i]) }
                ')' -> { depth--; if (depth == 0) break; body.append(text[i]) }
                else -> body.append(text[i])
            }
            i++
        }
        if (depth != 0) return null

        // The first `name :` in the fragment is the parameter. Modifiers vary
        // — `public val`, `private val`, a bare name, an annotation — so
        // enumerating them is a losing game; the colon is the reliable marker.
        return splitTopLevel(body.toString())
            .mapNotNull { Regex("""(\w+)\s*:""").find(it)?.groupValues?.get(1) }
            .toSet()
    }

    /**
     * Split on commas that are not inside parens, braces or brackets.
     *
     * Angle brackets are deliberately **not** tracked. Kotlin's `->` contains a
     * `>`, so counting it as a closing bracket drives the depth negative and
     * every subsequent split lands in the wrong place — which is how the first
     * version of this lint reported that `MutationOptions` accepts `context`
     * and `variables`, names it had scraped out of a lambda type. A generic
     * like `Map<String, Int>` may now split mid-type, but each fragment is only
     * mined for a leading `name :`, and fragments without one are ignored.
     */
    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        s.forEach { c ->
            when (c) {
                '(', '{', '[' -> { depth++; current.append(c) }
                ')', '}', ']' -> { depth--; current.append(c) }
                ',' -> if (depth == 0) { parts += current.toString(); current.clear() } else current.append(c)
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts
    }

    private fun kotlinBlocks(file: File): List<String> =
        Regex("""```kotlin\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .map { it.replace(Regex(""""(\\.|[^"\\])*""""), "\"\"").replace(Regex("""//.*"""), "") }
            .toList()

    @Test
    fun `the parameter index looks sane`() {
        assertTrue(parameters.size > 20, "only ${parameters.size} declarations parsed")
        val options = parameters["QueryOptions"].orEmpty()
        assertTrue("staleTime" in options && "gcTime" in options, "QueryOptions parsed as $options")
        assertTrue("refetchInterval" in options, "QueryOptions parsed as $options")
        assertTrue("key" in parameters["prefetchQuery"].orEmpty(), "prefetchQuery parsed wrong")
    }

    @Test
    fun `every named argument in the docs names a real parameter`() {
        val problems = mutableListOf<String>()

        docs.listFiles { f -> f.extension == "md" }.orEmpty().sorted().forEach { file ->
            kotlinBlocks(file).forEach { block ->
                Regex("""\b([A-Za-z]\w*)\s*\(""").findAll(block).forEach { call ->
                    val name = call.groupValues[1]
                    val known = parameters[name] ?: return@forEach
                    val open = block.indexOf('(', call.range.last - 1)
                    val args = argumentNames(block, open) ?: return@forEach
                    args.filterNot { it in known }.forEach { bad ->
                        problems += "${file.name}: $name($bad = …) — accepts ${known.sorted()}"
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            fail(
                "Named arguments that do not exist:\n" +
                    problems.distinct().joinToString("\n") { "  $it" },
            )
        }
    }

    /** Top-level `name =` arguments of a call whose open paren is at [open]. */
    private fun argumentNames(text: String, open: Int): List<String>? {
        if (open < 0 || text.getOrNull(open) != '(') return null
        var depth = 0
        var i = open
        val body = StringBuilder()
        while (i < text.length) {
            when (text[i]) {
                '(' -> { depth++; if (depth > 1) body.append(text[i]) }
                ')' -> { depth--; if (depth == 0) break; body.append(text[i]) }
                else -> body.append(text[i])
            }
            i++
        }
        if (depth != 0) return null

        return splitTopLevel(body.toString()).mapNotNull {
            // `a = b` but not `a == b`, and not a lambda's `it ->`.
            Regex("""^\s*(\w+)\s*=(?!=)""").find(it)?.groupValues?.get(1)
        }
    }
}
