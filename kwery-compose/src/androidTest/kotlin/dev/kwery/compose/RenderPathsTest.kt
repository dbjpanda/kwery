package dev.kwery.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kwery.QueryClient
import dev.kwery.QueryClientConfig
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.RetryPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What `state.isLoading` / `isError` / `isRefreshing` actually render as, in
 * a real Compose UI.
 *
 * `RememberQueryTest` (JVM, hand-driven composition) already covers
 * subscription and lambda-stability behaviour, and stays exactly as it is —
 * see the comment on this module's `build.gradle.kts`. This class covers the
 * one thing that needs an actual UI: that each `QueryState` reaches the
 * screen as the right visible content, on a real Compose measure/layout/draw
 * pass rather than a `collectAsState` value inspected in a test body.
 */
@RunWith(AndroidJUnit4::class)
class RenderPathsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun key(name: String): QueryKey<String> = object : QueryKey<String> {
        override val parts: List<Any?> = listOf("render-path-test", name)
    }

    @Composable
    private fun Screen(k: QueryKey<String>, fetcher: suspend () -> String) {
        val state = rememberQuery(k, options = QueryOptions(retry = RetryPolicy.Never), fetcher = fetcher)
        when {
            state.isLoading -> BasicText("Loading")
            state.isError -> BasicText("Error: ${state.error?.message}")
            state.isRefreshing -> BasicText("Refreshing: ${state.data}")
            else -> BasicText("Data: ${state.data}")
        }
    }

    @Test
    fun a_pending_fetch_renders_the_loading_state_then_switches_to_data() {
        val client = QueryClient(config = QueryClientConfig())
        val resolve = CompletableDeferred<String>()

        composeRule.setContent {
            CompositionLocalProvider(LocalQueryClient provides client) {
                Screen(key("success")) { resolve.await() }
            }
        }

        composeRule.onNodeWithText("Loading").assertIsDisplayed()

        resolve.complete("hello")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Data: hello").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun a_failed_fetch_renders_the_error_state() {
        val client = QueryClient(config = QueryClientConfig())

        composeRule.setContent {
            CompositionLocalProvider(LocalQueryClient provides client) {
                Screen(key("error")) { throw IllegalStateException("boom") }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Error: boom").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun a_background_refetch_renders_as_refreshing_while_old_data_stays_on_screen() {
        val client = QueryClient(config = QueryClientConfig())
        val k = key("refresh")
        var callCount = 0
        val secondFetch = CompletableDeferred<String>()

        composeRule.setContent {
            CompositionLocalProvider(LocalQueryClient provides client) {
                Screen(k) {
                    callCount += 1
                    if (callCount == 1) "v1" else secondFetch.await()
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Data: v1").fetchSemanticsNodes().isNotEmpty()
        }

        // Not runBlocking: invalidateQueries suspends until the refetch
        // completes, and the refetch here is deliberately held open on
        // secondFetch until after "Refreshing" is observed below. Blocking
        // this thread on it would deadlock against the very completion this
        // test drives itself.
        CoroutineScope(Dispatchers.Default).launch { client.invalidateQueries(k) }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Refreshing: v1").fetchSemanticsNodes().isNotEmpty()
        }

        secondFetch.complete("v2")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Data: v2").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
