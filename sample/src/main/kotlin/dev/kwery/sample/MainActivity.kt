package dev.kwery.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chuckerteam.chucker.api.Chucker
import dev.kwery.QueryState
import dev.kwery.compose.LocalQueryClient
import dev.kwery.compose.rememberQuery
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars and let the platform pick icon colours
        // that contrast with whatever is underneath them. Scaffold then insets
        // the content, so nothing ends up under the clock or the gesture bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as SampleApplication

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalQueryClient provides app.queryClient) {
                    TodoScaffold(app)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoScaffold(app: SampleApplication) {
    // Not in saved state on purpose. The toggle should reset with the process
    // so a cold start always begins in the mode you left it in conceptually,
    // not in a half-restored one.
    var useKwery by remember { mutableStateOf(true) }
    val context = LocalContext.current

    Scaffold(
        // Scaffold applies the status and navigation bar insets to the padding
        // it hands back, so nothing collides with the clock or the gesture bar.
        topBar = {
            TopAppBar(
                title = { Text("Todos") },
                actions = {
                    Text(
                        text = "Kwery",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Switch(
                        checked = useKwery,
                        onCheckedChange = { useKwery = it },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { insets ->
        TodoScreen(
            app = app,
            useKwery = useKwery,
            context = context,
            modifier = Modifier.padding(insets),
        )
    }
}

@Composable
private fun TodoScreen(
    app: SampleApplication,
    useKwery: Boolean,
    context: android.content.Context,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (useKwery) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Text(
                text = if (useKwery) {
                    "Kwery on. Retrofit still does the networking; Kwery decides when it runs."
                } else {
                    "Kwery off. Plain ViewModel and Retrofit, the usual way."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Button(
            onClick = { context.startActivity(Chucker.getLaunchIntent(context)) },
            modifier = Modifier.padding(bottom = 8.dp),
        ) { Text("Open the network log") }

        if (useKwery) WithKwery(app) else WithoutKwery(app)
    }
}

/**
 * The Kwery path. One call, and everything the toggle demonstrates comes from
 * the client's configuration rather than from code on this screen.
 */
@Composable
private fun WithKwery(app: SampleApplication) {
    val api = app.api
    val state: QueryState<List<RemoteTodo>> =
        rememberQuery(TodoListKey(limit = 5)) { api.todos(5) }

    if (state.isRefreshing) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }

    when {
        state.isLoading -> Loading()
        state.data != null -> TodoList(state.data!!)
        state.isError -> Text("Failed, and nothing cached to fall back on.")
    }

    state.error?.let {
        Text(
            text = "Last error: ${it.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    OfflineCard(app)
}

/** The baseline path. See [PlainTodosViewModel] for what it does and does not do. */
@Composable
private fun WithoutKwery(app: SampleApplication) {
    val vm: PlainTodosViewModel = viewModel(
        factory = viewModelFactory { initializer { PlainTodosViewModel(app.api) } },
    )
    val state by vm.state.collectAsState()

    when {
        state.loading && state.todos.isEmpty() -> Loading()
        state.todos.isNotEmpty() -> TodoList(state.todos)
        state.error != null -> Text("Failed: ${state.error}")
    }

    Text(
        text = "No cache on disk, nothing shared between screens, and a write " +
            "with no network is lost.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Loading from the network", modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TodoList(todos: List<RemoteTodo>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(todos, key = { it.id }) { todo ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (todo.completed) "✓ ${todo.title}" else todo.title,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The cache on disk and the queue of writes, both of which only exist in the
 * Kwery path. This card is the answer to "what do I get that a ViewModel does
 * not give me".
 */
@Composable
private fun OfflineCard(app: SampleApplication) {
    val scope = rememberCoroutineScope()
    val restored by app.restoredEntryCount.collectAsState()
    val pending by app.queue.pending.collectAsState()
    var lastSaved by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Works offline", style = MaterialTheme.typography.titleMedium)

            Text(
                text = when (restored) {
                    null -> "Loading the saved cache…"
                    0 -> "Cache on disk: empty. Kill the app and reopen it."
                    else -> "Cache on disk: $restored item(s), loaded on this launch"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Waiting to send: $pending", style = MaterialTheme.typography.bodyMedium)

            Text(
                text = "Mark one done. Works with no network:",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2).forEach { id ->
                    Button(
                        onClick = {
                            scope.launch {
                                // Returns once the write is on disk, not when
                                // it is delivered.
                                app.queue.submit(ToggleDoneKey, ToggleDone(id, done = true))
                                lastSaved = "todo $id"
                            }
                        },
                    ) { Text("Todo $id") }
                }
            }

            lastSaved?.let {
                Text(
                    text = "Saved $it. It will send when the network is back.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
