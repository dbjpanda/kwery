package dev.kwery.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kwery.QueryClient
import dev.kwery.QueryState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The ViewModel surface — the reason Kwery exists rather than using Soil.
 *
 * Nothing here is Kwery-specific ceremony. `client.query(...)` returns an
 * ordinary `Flow`, so it composes with `flatMapLatest` and `stateIn` exactly
 * like any other flow, and the standard Android pattern just works.
 */
class TodoDetailViewModel(
    private val client: QueryClient,
    private val api: FakeApi,
) : ViewModel() {

    private val selectedId = MutableStateFlow("1")

    @OptIn(ExperimentalCoroutinesApi::class)
    val todo: StateFlow<QueryState<Todo>> =
        selectedId
            // Switching keys cancels the old observer and subscribes the new
            // one; no manual bookkeeping.
            .flatMapLatest { id -> client.query(TodoKey(id)) { api.todo(id) } }
            .stateIn(
                scope = viewModelScope,
                // Rotation is free: the upstream stays alive across the
                // configuration change, so the cache never even sees a detach.
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = QueryState(),
            )

    fun select(id: String) {
        selectedId.value = id
    }

    fun refresh() {
        viewModelScope.launch { client.invalidateQueries(TodoKey(selectedId.value)) }
    }
}
