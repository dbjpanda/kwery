package dev.kwery.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The baseline: how this screen is normally written without Kwery.
 *
 * Deliberately not a straw man. This is the shape Google's own architecture
 * guidance produces, and it is genuinely good at the thing people usually test
 * it on: the ViewModel outlives configuration changes, so rotating the device
 * does not refetch. Anyone claiming Kwery fixes rotation against *this*
 * baseline would be overselling.
 *
 * What it does not do, and what the toggle in the sample makes visible:
 *
 * - **Nothing survives process death.** The ViewModel dies with the process,
 *   so a cold start is a spinner and a network call, every time.
 * - **Nothing is shared.** A second screen asking for the same data gets its
 *   own instance and its own request.
 * - **A write with no network is simply lost**, or surfaces as an error the
 *   user has to handle.
 */
class PlainTodosViewModel(private val api: TodoApi) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val todos: List<RemoteTodo> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                _state.value = State(todos = api.todos(), loading = false)
            } catch (failure: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = failure.message ?: failure::class.simpleName,
                )
            }
        }
    }
}
