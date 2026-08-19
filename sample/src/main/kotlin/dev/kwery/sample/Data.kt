package dev.kwery.sample

import dev.kwery.persist.DurableMutationKey
import dev.kwery.persist.PersistableQueryKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** Exactly the shape jsonplaceholder.typicode.com returns. */
@Serializable
data class RemoteTodo(
    val id: Int,
    val title: String,
    val completed: Boolean,
)

/** Body for the write the offline queue replays. */
@Serializable
data class CompletedPatch(val completed: Boolean)

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
data class TodoListKey(val limit: Int) : PersistableQueryKey<List<RemoteTodo>> {
    override val parts get() = listOf("todos", mapOf("limit" to limit))
    override val serializer: KSerializer<List<RemoteTodo>> get() = serializer()
}

data class TodoKey(val id: Int) : PersistableQueryKey<RemoteTodo> {
    override val parts get() = listOf("todos", "detail", id)
    override val serializer: KSerializer<RemoteTodo> get() = serializer()
}

/** Variables for a write that has to outlive the process. */
@Serializable
data class ToggleDone(val id: Int, val done: Boolean)

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
