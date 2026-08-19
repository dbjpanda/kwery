package dev.kwery.sample

import dev.kwery.persist.DurableMutationKey
import dev.kwery.persist.PersistableQueryKey
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
data class Todo(val id: String, val title: String, val done: Boolean)

/**
 * A key names the type it produces, so nothing downstream needs a cast.
 *
 * `parts` is what filters and persistence use; `TodoListKey` and `TodoKey`
 * share the `"todos"` prefix so `invalidateQueries("todos")` matches both.
 *
 * These are [PersistableQueryKey] rather than plain `QueryKey` because they are
 * meant to survive process death. A stored entry arrives from disk as bytes
 * with no type, so only the key can say how to decode it. A key that should
 * never touch disk simply stays a `QueryKey`, which makes "this is persisted"
 * visible at the declaration instead of hidden in configuration.
 */
data class TodoListKey(val onlyOpen: Boolean) : PersistableQueryKey<List<Todo>> {
    override val parts get() = listOf("todos", mapOf("onlyOpen" to onlyOpen))
    override val serializer: KSerializer<List<Todo>> get() = serializer()
}

data class TodoKey(val id: String) : PersistableQueryKey<Todo> {
    override val parts get() = listOf("todos", "detail", id)
    override val serializer: KSerializer<Todo> get() = serializer()
}

/** Variables for a write that has to outlive the process. */
@Serializable
data class ToggleDone(val id: String, val done: Boolean)

/**
 * A durable write, registered once at startup.
 *
 * Serialized state cannot carry code, so a queued write stores its *variables*
 * and the identity of the function to run. That function is registered when the
 * queue is built, away from any screen, because a write replayed after a cold
 * start has no UI left to get it from.
 */
object ToggleDoneKey : DurableMutationKey<ToggleDone> {
    override val parts get() = listOf("todos", "toggle-done")
    override val serializer: KSerializer<ToggleDone> get() = serializer()
}

/**
 * Stands in for a network layer.
 *
 * Deliberately slow and occasionally failing: the states worth demonstrating —
 * a background refresh with content still on screen, an error that keeps the
 * previous data — only appear when requests take time and sometimes go wrong.
 */
class FakeApi {
    private var counter = 0

    private val todos = mutableListOf(
        Todo("1", "Read the roadmap", done = true),
        Todo("2", "Rotate the screen and watch the request count", done = false),
        Todo("3", "Turn off wifi and watch it pause, not fail", done = false),
    )

    var failNextRequest: Boolean = false

    /**
     * Every delivered write appends a line here, so the screen can show that a
     * write made while offline really did reach the "server" later, rather than
     * asking the viewer to take it on trust.
     */
    val delivered = mutableListOf<String>()

    suspend fun todos(onlyOpen: Boolean): List<Todo> {
        delay(900)
        if (failNextRequest) {
            failNextRequest = false
            throw IllegalStateException("the network let you down")
        }
        counter++
        val fresh = todos + Todo("gen-$counter", "Fetched $counter time(s)", done = false)
        return if (onlyOpen) fresh.filter { !it.done } else fresh
    }

    suspend fun todo(id: String): Todo {
        delay(600)
        return todos.firstOrNull { it.id == id }
            ?: Todo(id, "Unknown todo $id", done = false)
    }

    suspend fun setDone(id: String, done: Boolean) {
        delay(400)
        val index = todos.indexOfFirst { it.id == id }
        if (index >= 0) todos[index] = todos[index].copy(done = done)
        delivered += "#$id -> done=$done"
    }
}
