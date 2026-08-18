package dev.kwery.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kwery.OnlineManager
import dev.kwery.TimeSource
import dev.kwery.persist.DurableMutationKey
import dev.kwery.persist.FileMutationQueueStore
import dev.kwery.persist.OfflineQueue
import dev.kwery.persist.OfflineQueueOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class AddTodo(val title: String)

private object AddTodoKey : DurableMutationKey<AddTodo> {
    override val parts get() = listOf("todos", "add")
    override val serializer get() = serializer<AddTodo>()
}

private class FixedOnline(initial: Boolean) : OnlineManager {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(initial)
}

/**
 * Proves the offline queue survives an actual OS process kill, not just a
 * fresh object reopening the same store inside the same JVM — every other
 * persistence test in this project leaves that gap open by construction,
 * because JUnit has no way to kill and relaunch a process from inside a test.
 *
 * This class is instead driven by two separate `am instrument` invocations,
 * with a real `am force-stop` between them: see
 * `scripts/process-kill-test.sh`. The two methods only make sense run in that
 * order, from a clean queue file, which is why neither is a normal
 * self-contained `@Test`.
 */
@RunWith(AndroidJUnit4::class)
class ProcessKillQueueTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val queueFile: File
        get() = File(context.filesDir, "kwery-process-kill-test/queue.json")

    @Test
    fun step1_writeThenAwaitKill() = runBlocking {
        queueFile.parentFile?.mkdirs()
        val store = FileMutationQueueStore(queueFile)

        // Offline, so the write is enqueued and never delivered before the
        // real kill happens outside this process — exactly the window this
        // feature exists to close.
        val queue = OfflineQueue(
            scope = CoroutineScope(SupervisorJob()),
            options = OfflineQueueOptions(store = store),
            onlineManager = FixedOnline(initial = false),
            timeSource = TimeSource.System,
        ) {
            register(AddTodoKey) { /* never reached in this process */ }
        }
        queue.submit(AddTodoKey, AddTodo("survive a real kill"), id = "kill-test-1")

        assertEquals(listOf("kill-test-1"), store.all().map { it.id })
    }

    @Test
    fun step2_verifyAfterRealKillThenDeliver() = runBlocking {
        val store = FileMutationQueueStore(queueFile)
        val onDisk = store.all()
        assertEquals(
            listOf("kill-test-1"),
            onDisk.map { it.id },
            "the write from step 1 must still be on disk after a real OS process kill",
        )

        val delivered = CompletableDeferred<AddTodo>()
        val queue = OfflineQueue(
            scope = CoroutineScope(SupervisorJob()),
            options = OfflineQueueOptions(store = store),
            onlineManager = FixedOnline(initial = true),
            timeSource = TimeSource.System,
        ) {
            register(AddTodoKey) { todo -> delivered.complete(todo) }
        }
        queue.resume()

        val result = withTimeout(10.seconds) { delivered.await() }
        assertEquals(AddTodo("survive a real kill"), result)
        assertTrue(store.all().isEmpty(), "a delivered write is removed from the durable store")
    }
}
