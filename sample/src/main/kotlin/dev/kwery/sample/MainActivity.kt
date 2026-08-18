package dev.kwery.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kwery.QueryState
import dev.kwery.prefetchQuery
import dev.kwery.compose.LocalQueryClient
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kwery.compose.rememberQuery
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SampleApplication

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalQueryClient provides app.queryClient) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        TodoScreen(api = app.api)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoScreen(api: FakeApi) {
    val client = LocalQueryClient.current
    val scope = rememberCoroutineScope()

    // Kept in saved state so it survives rotation. The query KEY belongs here;
    // the query DATA does not — that is the cache's job, and putting response
    // bodies in saved state risks TransactionTooLargeException.
    var onlyOpen by rememberSaveable { mutableStateOf(false) }
    var failNext by remember { mutableStateOf(false) }

    val key = TodoListKey(onlyOpen)
    val state: QueryState<List<Todo>> = rememberQuery(key) { api.todos(onlyOpen) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        Text("Kwery sample", style = MaterialTheme.typography.headlineSmall)

        StatusLine(state)

        // isRefreshing is success + fetching: content is on screen and being
        // refreshed underneath. A single Loading/Success/Error enum cannot
        // express this, which is why there are two status axes.
        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }

        DetailFromViewModel(api)
        PrefetchOnNavigate(api)

        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = onlyOpen,
                onClick = { onlyOpen = !onlyOpen },
                label = { Text("Only open") },
            )
            Button(onClick = {
                // Prefix match: hits every key starting with "todos", so both
                // the list and any detail queries refresh.
                scope.launch { client.invalidateQueries("todos") }
            }) {
                Text("Invalidate")
            }
            Button(onClick = {
                failNext = true
                api.failNextRequest = true
                scope.launch { client.invalidateQueries("todos") }
            }) {
                Text("Fail next")
            }
        }

        // isLoading is pending + fetching — a first load actually in flight.
        // isPending alone would show a spinner forever for a disabled query.
        if (state.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text("First load", modifier = Modifier.padding(top = 8.dp))
            }
            return@Column
        }

        val todos = state.data
        if (todos != null) {
            // Data survives an error, so a failed refresh shows the error
            // banner above WITHOUT blanking the list.
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(todos, key = { it.id }) { todo ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (todo.done) "✓ ${todo.title}" else todo.title,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        } else if (state.isError) {
            Text("Failed, and there is no cached data to fall back on.")
        }

        if (failNext) {
            Text(
                text = "The next request will fail — watch the list stay on screen.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The same cache, observed from a ViewModel instead of a composable.
 *
 * Both surfaces share one entry and one in-flight request — that is the point
 * of the Flow-first core.
 */
@Composable
private fun DetailFromViewModel(api: FakeApi) {
    val client = LocalQueryClient.current
    val viewModel: TodoDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { TodoDetailViewModel(client, api) }
        },
    )
    val state by viewModel.todo.collectAsState()

    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("From a ViewModel", style = MaterialTheme.typography.titleSmall)
            Text(
                text = state.data?.title ?: if (state.isLoading) "Loading…" else "—",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.select("2") }) { Text("Select #2") }
                Button(onClick = { viewModel.refresh() }) { Text("Refresh") }
            }
        }
    }
}

/**
 * Prefetch in the click handler, before navigating.
 *
 * The request overlaps the transition animation — usually 200-300ms of latency
 * you get for free — and the destination renders with data already in the
 * cache. Two details make this safe rather than clever:
 *
 * - it is `launch`ed, so prefetching never delays the navigation itself;
 * - `prefetchQuery` respects `staleTime` and never throws, so calling it on
 *   every tap costs nothing when the data is warm and cannot crash the handler
 *   when the network is down.
 */
@Composable
private fun PrefetchOnNavigate(api: FakeApi) {
    val client = LocalQueryClient.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Prefetch before navigating", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1", "2").forEach { id ->
                    Button(
                        onClick = {
                            scope.launch { client.prefetchQuery(TodoKey(id)) { api.todo(id) } }
                            opened = id
                        },
                    ) { Text("Open #" + id) }
                }
            }
            val id = opened
            if (id != null) {
                val state = rememberQuery(TodoKey(id)) { api.todo(id) }
                Text(
                    text = if (state.isLoading) "Loading…" else state.data?.title ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Opened with no spinner if the prefetch had landed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Renders both status axes, which is the point of the sample. */
@Composable
private fun StatusLine(state: QueryState<List<Todo>>) {
    Text(
        text = "status=${state.status}  fetchStatus=${state.fetchStatus}",
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.isPaused) {
        Text(
            text = "Paused — no validated network. Not an error: it will resume.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    state.error?.let {
        Text(
            text = "Last error: ${it.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
