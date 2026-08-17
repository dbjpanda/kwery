package dev.kwery.sample

import dev.kwery.QueryKey
import kotlinx.coroutines.delay

data class Todo(val id: String, val title: String, val done: Boolean)

/**
 * A key names the type it produces, so nothing downstream needs a cast.
 *
 * `parts` is what filters and persistence use; `TodoListKey` and `TodoKey`
 * share the `"todos"` prefix so `invalidateQueries("todos")` matches both.
 */
data class TodoListKey(val onlyOpen: Boolean) : QueryKey<List<Todo>> {
    override val parts get() = listOf("todos", mapOf("onlyOpen" to onlyOpen))
}

data class TodoKey(val id: String) : QueryKey<Todo> {
    override val parts get() = listOf("todos", "detail", id)
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
    }
}
