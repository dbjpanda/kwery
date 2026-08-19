package dev.kwery.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
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
        // that contrast with what is underneath. Scaffold insets the content,
        // so nothing lands under the clock or the gesture bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as SampleApplication

        setContent {
            SampleTheme {
                CompositionLocalProvider(LocalQueryClient provides app.queryClient) {
                    TodoApp(app)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoApp(app: SampleApplication) {
    var useKwery by remember { mutableStateOf(true) }
    var showDemo by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text("Today", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = { showDemo = !showDemo }) {
                        Dot(
                            colour = if (showDemo) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 20.dp),
        ) {
            AnimatedVisibility(visible = showDemo) {
                DemoPanel(
                    useKwery = useKwery,
                    onToggle = { useKwery = it },
                    onOpenLog = { context.startActivity(Chucker.getLaunchIntent(context)) },
                    app = app,
                )
            }

            if (useKwery) KweryList(app) else PlainList(app)
        }
    }
}

/** A small round swatch, used as the demo-panel toggle in the app bar. */
@Composable
private fun Dot(colour: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(colour),
    )
}

// ---------------------------------------------------------------------------
// The list, in both modes
// ---------------------------------------------------------------------------

@Composable
private fun KweryList(app: SampleApplication) {
    val api = app.api
    val client = LocalQueryClient.current
    val scope = rememberCoroutineScope()
    val pending by app.queue.pending.collectAsState()
    val state: QueryState<List<RemoteTodo>> =
        rememberQuery(TodoListKey(limit = 5)) { api.todos(5) }

    val todos = state.data

    Column(modifier = Modifier.fillMaxSize()) {
        ProgressHeader(todos, refreshing = state.isRefreshing, pending = pending)

        when {
            state.isLoading -> Loading()
            todos != null -> TodoList(
                todos = todos,
                // Tapping a row is the natural gesture, and it is also the
                // thing worth demonstrating: the write goes to a durable queue
                // rather than straight to the network, so it survives having
                // no signal and survives the process being killed.
                onToggle = { todo ->
                    scope.launch {
                        val next = !todo.completed
                        // Flip it on screen first. The user gets an instant
                        // response whether or not there is a network, which is
                        // the only honest way to present a write that may not
                        // be delivered for hours.
                        client.setQueryData(TodoListKey(limit = 5)) { current ->
                            current?.map { if (it.id == todo.id) it.copy(completed = next) else it }
                        }
                        // Then durably queue the real write.
                        app.queue.submit(ToggleDoneKey, ToggleDone(todo.id, next))
                    }
                },
            )
            state.isError -> Empty("Could not load, and nothing cached to fall back on.")
        }
    }
}

@Composable
private fun PlainList(app: SampleApplication) {
    val vm: PlainTodosViewModel = viewModel(
        factory = viewModelFactory { initializer { PlainTodosViewModel(app.api) } },
    )
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        ProgressHeader(state.todos.ifEmpty { null }, refreshing = false, pending = 0)

        when {
            state.loading && state.todos.isEmpty() -> Loading()
            state.todos.isNotEmpty() -> TodoList(
                todos = state.todos,
                // No queue here, so a tap with no network is simply lost. That
                // is the point of the comparison, not an oversight.
                onToggle = { },
            )
            state.error != null -> Empty("Could not load: ${state.error}")
        }
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun ProgressHeader(todos: List<RemoteTodo>?, refreshing: Boolean, pending: Int) {
    val done = todos?.count { it.completed } ?: 0
    val total = todos?.size ?: 0

    Column(modifier = Modifier.padding(bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (total == 0) "Nothing yet" else "$done of $total done",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pending > 0) {
                Spacer(Modifier.width(10.dp))
                Pill("$pending waiting to send")
            }
        }

        Spacer(Modifier.height(10.dp))

        // A single hairline that fills as things are completed. Reads as
        // progress rather than as a loading spinner.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (total > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(done / total.toFloat())
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        if (refreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
    }
}

@Composable
private fun TodoList(todos: List<RemoteTodo>, onToggle: (RemoteTodo) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(todos, key = { it.id }) { todo ->
            TodoRow(todo, onToggle)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TodoRow(todo: RemoteTodo, onToggle: (RemoteTodo) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable { onToggle(todo) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkmark(done = todo.completed)
            Spacer(Modifier.width(14.dp))
            Text(
                text = todo.title.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                color = if (todo.completed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (todo.completed) TextDecoration.LineThrough else null,
            )
        }
    }
}

/** A ring that fills when the item is done. Drawn rather than an icon font. */
@Composable
private fun Checkmark(done: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (done) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(
        shape = RoundedCornerShape(100),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        Text(
            text = "Loading from the network",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun Empty(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 32.dp),
    )
}

// ---------------------------------------------------------------------------
// The demo controls, folded away behind the app-bar dot
// ---------------------------------------------------------------------------

@Composable
private fun DemoPanel(
    useKwery: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenLog: () -> Unit,
    app: SampleApplication,
) {
    val restored by app.restoredEntryCount.collectAsState()

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = if (useKwery) "Kwery is on" else "Kwery is off",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = if (useKwery) {
                            "Retrofit still does the networking. Kwery decides when it runs."
                        } else {
                            "Plain ViewModel and Retrofit, the usual way."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useKwery, onCheckedChange = onToggle)
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = when {
                    !useKwery -> "Nothing on disk. A cold start always hits the network."
                    restored == null -> "Loading the saved cache…"
                    restored == 0 -> "Cache on disk: empty. Kill the app and reopen it."
                    else -> "Cache on disk: $restored item(s), loaded on this launch."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(100),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpenLog() },
            ) {
                Text(
                    text = "Open the network log",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}
