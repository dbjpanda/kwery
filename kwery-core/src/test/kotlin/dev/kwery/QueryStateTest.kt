package dev.kwery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The two status axes are the design decision most likely to be "simplified"
 * into a single sealed class by a well-meaning refactor (AD-4). These tests
 * pin every reachable combination so that would fail loudly.
 */
class QueryStateTest {

    private fun state(
        data: String? = null,
        error: Throwable? = null,
        status: QueryStatus = QueryStatus.Pending,
        fetchStatus: FetchStatus = FetchStatus.Idle,
    ) = QueryState(data = data, error = error, status = status, fetchStatus = fetchStatus)

    @Test
    fun `cold start in flight is pending and fetching`() {
        val s = state(status = QueryStatus.Pending, fetchStatus = FetchStatus.Fetching)
        assertTrue(s.isPending)
        assertTrue(s.isFetching)
        assertTrue(s.isLoading, "isLoading drives the spinner and must be true here")
        assertFalse(s.isRefreshing)
    }

    @Test
    fun `cold start offline is pending and paused, not loading`() {
        // The combination a single Loading|Success|Error enum cannot express.
        val s = state(status = QueryStatus.Pending, fetchStatus = FetchStatus.Paused)
        assertTrue(s.isPending)
        assertTrue(s.isPaused)
        assertFalse(s.isFetching)
        assertFalse(s.isLoading, "paused is not loading — no request is running")
    }

    @Test
    fun `background refresh is success and fetching`() {
        // The other combination a single enum cannot express.
        val s = state(data = "cached", status = QueryStatus.Success, fetchStatus = FetchStatus.Fetching)
        assertTrue(s.isSuccess)
        assertTrue(s.isFetching)
        assertTrue(s.isRefreshing)
        assertFalse(s.isLoading, "content is on screen; this is not a first load")
    }

    @Test
    fun `settled success is idle`() {
        val s = state(data = "d", status = QueryStatus.Success, fetchStatus = FetchStatus.Idle)
        assertTrue(s.isSuccess)
        assertFalse(s.isFetching)
        assertFalse(s.isLoading)
        assertFalse(s.isRefreshing)
    }

    @Test
    fun `disabled query with no data is pending but never loading`() {
        // The regression test for the spinner-forever bug: `enabled = false`
        // leaves a query Pending + Idle indefinitely.
        val s = state(status = QueryStatus.Pending, fetchStatus = FetchStatus.Idle)
        assertTrue(s.isPending)
        assertFalse(s.isLoading)
    }

    @Test
    fun `data is retained when status becomes error`() {
        // A failed background refetch must not blank a screen showing content,
        // so status == Error does NOT imply data == null (feature 03, OQ-1).
        val s = state(data = "stale but valid", error = RuntimeException("boom"), status = QueryStatus.Error)
        assertTrue(s.isError)
        assertEquals("stale but valid", s.data)
    }

    @Test
    fun `failureReason is populated while retries continue and error is not`() {
        val s = QueryState<String>(
            status = QueryStatus.Pending,
            fetchStatus = FetchStatus.Fetching,
            failureCount = 2,
            failureReason = RuntimeException("attempt failed"),
            error = null,
        )
        assertEquals(2, s.failureCount)
        assertTrue(s.failureReason != null)
        assertTrue(s.error == null, "error stays null until the final attempt fails")
    }

    @Test
    fun `every combination of the two axes is constructible and consistent`() {
        for (status in QueryStatus.entries) {
            for (fetchStatus in FetchStatus.entries) {
                val s = state(status = status, fetchStatus = fetchStatus)
                assertEquals(status == QueryStatus.Pending, s.isPending)
                assertEquals(status == QueryStatus.Error, s.isError)
                assertEquals(status == QueryStatus.Success, s.isSuccess)
                assertEquals(fetchStatus == FetchStatus.Fetching, s.isFetching)
                assertEquals(fetchStatus == FetchStatus.Paused, s.isPaused)
                assertEquals(s.isPending && s.isFetching, s.isLoading)
                assertEquals(s.isSuccess && s.isFetching, s.isRefreshing)
            }
        }
    }

    // ---- UI projection ---------------------------------------------------

    @Test
    fun `toUiState maps a first load to Loading`() {
        assertIs<QueryUiState.Loading>(
            state(status = QueryStatus.Pending, fetchStatus = FetchStatus.Fetching).toUiState(),
        )
    }

    @Test
    fun `toUiState maps data to Content and carries the refreshing flag`() {
        val content = state(data = "d", status = QueryStatus.Success, fetchStatus = FetchStatus.Fetching)
            .toUiState()
        assertIs<QueryUiState.Content<String>>(content)
        assertEquals("d", content.data)
        assertTrue(content.isRefreshing)
    }

    @Test
    fun `toUiState prefers retained data over an error`() {
        // Showing stale content with a refresh indicator beats blanking the
        // screen on a transient failure.
        val projected = state(
            data = "stale",
            error = RuntimeException("boom"),
            status = QueryStatus.Error,
        ).toUiState()
        assertIs<QueryUiState.Content<String>>(projected)
        assertEquals("stale", projected.data)
    }

    @Test
    fun `toUiState maps an error with no data to Failed`() {
        val projected = state(error = RuntimeException("boom"), status = QueryStatus.Error).toUiState()
        assertIs<QueryUiState.Failed>(projected)
    }
}
