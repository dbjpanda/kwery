package dev.kwery.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every Kwery identifier used in `docs/` must actually exist.
 *
 * Documentation examples rot silently: nothing compiles them, so a renamed
 * parameter or a method that was only ever proposed sits in the docs looking
 * authoritative. That has already happened three times in this project — a
 * `currentQueuedMutationId` that did not exist, a `PlaceholderData.KeepPrevious`
 * that was never built, and a `prefetchQuery(key, staleTime, fetcher)` overload
 * taken from a design sketch rather than the code.
 *
 * This reads the committed `.api` dumps — the same files `apiCheck` enforces —
 * and holds the prose to them. It is a lint, not a compiler: it cannot catch a
 * wrong argument *order*, only a name that is not there. That is deliberate;
 * catching most of the rot automatically beats catching all of it never.
 */
class DocsApiLintTest {

    private val root = File(System.getProperty("kwery.repoRoot"))
    private val docs = File(root, "docs")

    /** Every simple name the published API exposes. */
    private val apiNames: Set<String> by lazy {
        val names = mutableSetOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "api" }
            .forEach { dump ->
                dump.readLines().forEach { line ->
                    // fun foo (…)  /  field Bar Ldev/kwery/…  /  class dev/kwery/Baz
                    Regex("""fun ([a-zA-Z_][\w-]*)""").find(line)?.let { names += it.groupValues[1] }
                    Regex("""field ([a-zA-Z_]\w*)""").find(line)?.let { names += it.groupValues[1] }
                    Regex("""class ([\w/$]+)""").find(line)?.let {
                        names += it.groupValues[1].substringAfterLast('/').split('$')
                    }
                }
            }
        // A Kotlin property appears as getX in the dump. Expose both the
        // lower-case form (`client.isFetching`) and the original capitalisation
        // (`RetryDelay.Default`), because companion-object properties are
        // referenced with the capital they were declared with.
        names += names.filter { it.startsWith("get") && it.length > 3 }
            .flatMap { getter ->
                val bare = getter.removePrefix("get")
                listOf(bare, bare.replaceFirstChar(Char::lowercaseChar))
            }
        names.map { it.substringBefore('-') }.toSet()   // drop inline-class mangling
    }

    /**
     * Fenced Kotlin blocks, with string literals and comments blanked out.
     *
     * Without that, `Text("Retry")` reads as a reference to a type called
     * `Retry` and the lint reports a doc error that is not one. A lint that
     * cries wolf gets switched off, so precision matters more than reach.
     */
    private fun kotlinBlocks(file: File): List<String> =
        Regex("""```kotlin\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .map { block ->
                block
                    .replace(Regex(""""(\\.|[^"\\])*""""), "\"\"")
                    .replace(Regex("""//.*"""), "")
            }
            .toList()

    @Test
    fun `the docs directory is where this test thinks it is`() {
        assertTrue(docs.isDirectory, "no docs/ at ${docs.absolutePath}")
        assertTrue(apiNames.size > 100, "only ${apiNames.size} API names parsed — the dumps moved?")
        assertTrue("prefetchQuery" in apiNames && "QueryClient" in apiNames, "sanity check failed")
    }

    @Test
    fun `every method called on a client or query in the docs exists`() {
        // Receivers whose members are Kwery's, so an unknown name is a bug
        // rather than the reader's own code.
        val receiver = Regex("""\b(client|kwery|kwery\.client|query)\.([a-zA-Z]\w*)""")
        val problems = mutableListOf<String>()

        docs.listFiles { f -> f.extension == "md" }.orEmpty().sorted().forEach { file ->
            kotlinBlocks(file).forEach { block ->
                receiver.findAll(block).forEach { m ->
                    val name = m.groupValues[2]
                    if (name !in apiNames) problems += "${file.name}: ${m.value}"
                }
            }
        }

        if (problems.isNotEmpty()) {
            fail("Documented members that do not exist:\n" + problems.joinToString("\n") { "  $it" })
        }
    }

    @Test
    fun `every enum constant referenced in the docs exists`() {
        val enums = listOf(
            "NetworkMode", "RefetchOn", "QueryStatus", "FetchStatus",
            "MutationStatus", "QueryType", "StaleTime", "RetryPolicy", "RetryDelay",
        )
        val problems = mutableListOf<String>()

        docs.listFiles { f -> f.extension == "md" }.orEmpty().sorted().forEach { file ->
            kotlinBlocks(file).forEach { block ->
                enums.forEach { type ->
                    Regex("""\b$type\.([A-Za-z]\w*)""").findAll(block).forEach { m ->
                        val member = m.groupValues[1]
                        if (member !in apiNames) problems += "${file.name}: $type.$member"
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            fail("Documented members that do not exist:\n" + problems.joinToString("\n") { "  $it" })
        }
    }

    @Test
    fun `every Kwery type named in the docs exists`() {
        val known = apiNames
        val problems = mutableListOf<String>()
        // Types Kwery owns are the ones worth checking; a reader's own `Todo`
        // or `api` is not our business.
        val kweryish = Regex("""\b(Query|Mutation|Infinite|Persist|Durable|Aggregate|Stale|Retry|Network|Refetch|Dehydrated|Optimistic)[A-Za-z]*\b""")

        docs.listFiles { f -> f.extension == "md" }.orEmpty().sorted().forEach { file ->
            kotlinBlocks(file).forEach { block ->
                kweryish.findAll(block).forEach { m ->
                    val name = m.value
                    if (name !in known) problems += "${file.name}: $name"
                }
            }
        }

        if (problems.isNotEmpty()) {
            fail("Documented types that do not exist:\n" + problems.distinct().joinToString("\n") { "  $it" })
        }
    }
}
