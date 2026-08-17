# 03 — Query State & Status Axes

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/queries.md`](../../.reference/tanstack-query/docs/framework/react/guides/queries.md), [`guides/disabling-queries.md`](../../.reference/tanstack-query/docs/framework/react/guides/disabling-queries.md) |
| **Blocks** | everything |
| **Decision** | AD-4 (two orthogonal status axes) |

The shape of the state object every consumer reads. Getting this wrong makes
every downstream feature awkward, which is why AD-4 exists.

## TanStack behaviour

Two **orthogonal** axes, deliberately not merged:

- `status` ∈ `pending` | `error` | `success` — *do we have data?*
- `fetchStatus` ∈ `fetching` | `paused` | `idle` — *is the query function running?*

Quoting the guide directly: "The `status` gives information about the `data`: Do
we have any or not? The `fetchStatus` gives information about the `queryFn`: Is
it running or not?"

All combinations are reachable. `success` + `fetching` is a background refetch.
`pending` + `paused` is a first-ever load with no network connection.

Derived flags:

- `isFetching` — fetching in any status, including background refetches.
- `isLoading` — **derived as `isPending && isFetching`**. This is the flag that
  actually drives a spinner; `isPending` alone is wrong for disabled or lazy
  queries, which sit in `pending` forever without fetching.
- `failureReason` — holds the error during retry attempts, before the final
  attempt promotes it to `error`. Lets the UI say "retrying…" with a reason.

`enabled: false` behaviour:

- With cached data → starts in `success`.
- Without cached data → `pending` + `fetchStatus: idle` (**not** `fetching`).
- No fetch on mount, no background refetch.
- **Ignores `invalidateQueries` and `refetchQueries`.**
- Manual `refetch()` still works (but not with `skipToken`).

## Kwery design

```kotlin
data class QueryState<T>(
    val data: T? = null,
    val error: Throwable? = null,
    val status: QueryStatus = QueryStatus.Pending,
    val fetchStatus: FetchStatus = FetchStatus.Idle,
    val failureCount: Int = 0,
    val failureReason: Throwable? = null,
    val dataUpdatedAt: Long? = null,
    val errorUpdatedAt: Long? = null,
    val isInvalidated: Boolean = false,
) {
    val isPending get() = status == QueryStatus.Pending
    val isError   get() = status == QueryStatus.Error
    val isSuccess get() = status == QueryStatus.Success

    val isFetching get() = fetchStatus == FetchStatus.Fetching
    val isPaused   get() = fetchStatus == FetchStatus.Paused

    /** True only on a first-ever load that is actually in flight. Use this for spinners. */
    val isLoading get() = isPending && isFetching

    /** True when showing data that is being refreshed underneath. */
    val isRefreshing get() = isSuccess && isFetching
}

enum class QueryStatus { Pending, Error, Success }
enum class FetchStatus { Fetching, Paused, Idle }
```

`QueryState` is a `data class`, not a sealed class. This is the central design
decision and it deserves justification, because sealed classes are the idiomatic
Kotlin reflex here.

A sealed `Loading | Success | Error` hierarchy cannot express `success` +
`fetching` (background refetch with data on screen) or `pending` + `paused`
(cold start, offline) without either duplicating `data` into several subclasses
or adding flags to them — at which point it is a data class wearing a costume.
Two orthogonal enums model two orthogonal questions honestly.

The cost is real and should be acknowledged: consumers lose exhaustive `when`
and smart-casting on `data`. Kwery mitigates this with an opt-in adapter for
consumers who want the sealed shape and can accept its lossiness:

```kotlin
sealed interface QueryUiState<out T> {
    data object Loading : QueryUiState<Nothing>
    data class Content<T>(val data: T, val isRefreshing: Boolean) : QueryUiState<T>
    data class Failed(val error: Throwable, val isRetrying: Boolean) : QueryUiState<Nothing>
}

fun <T> QueryState<T>.toUiState(): QueryUiState<T>
```

This keeps the lossy projection where it belongs — at the UI boundary, chosen
explicitly — rather than baking it into the core.

### Disabling

```kotlin
client.query(TodoListKey(filter), enabled = filter.isNotEmpty())
```

`enabled = false` matches TanStack exactly, including ignoring invalidation.
`skipToken` has no Kwery analogue: a null `QueryFn` is already type-safe, so
`enabled` alone covers both cases.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `status` 3-state | yes | `QueryStatus` | planned |
| `fetchStatus` 3-state | yes | `FetchStatus` | planned |
| Axes orthogonal | yes | yes | planned |
| `isLoading` = `isPending && isFetching` | yes | yes | planned |
| `isFetching` in any status | yes | yes | planned |
| `failureCount` / `failureReason` | yes | yes | planned |
| `dataUpdatedAt` / `errorUpdatedAt` | yes | yes | planned |
| `enabled: false` | yes | `enabled` param | planned |
| `enabled: false` ignores invalidation | yes | yes | planned |
| `skipToken` | yes | not needed — `QueryFn` nullability is type-safe | divergent |
| Sealed UI projection | no | `toUiState()`, opt-in | divergent (addition) |

## Deliberate divergences

1. **Data class, not sealed class.** Justified above. Expect pushback from
   Kotlin reviewers; the reasoning belongs in the public docs, not just here.
2. **No `skipToken`.** It exists to work around TypeScript's inability to narrow
   `queryFn` types. Kotlin's nullability handles it.
3. **`isRefreshing` added.** `isSuccess && isFetching` is the single most common
   derived check in real UIs and TanStack makes users write it by hand.

## Open questions

- **OQ-1.** ~~Should `data` survive a transition to `error`?~~ **Closed: yes,
  data is always retained.** A background refetch failing must never blank a
  screen that is currently showing valid data — that turns a transient network
  error into a total content loss, which is strictly worse than showing slightly
  stale content with an error indicator.

  The consequence is real and must be stated loudly rather than buried:
  **`status == Error` does not imply `data == null`.** This goes in the KDoc for
  both `status` and `data`, and has a dedicated test. It is also why the sealed
  `QueryUiState` projection carries `Failed` *and* keeps `Content` reachable —
  a UI showing cached data with an error banner is the correct rendering, not an
  edge case.

- **OQ-2.** ~~`Long` epoch millis or `kotlin.time.Instant`?~~ **Closed: `Long`
  epoch millis throughout the public API.**

  `kotlin.time.Instant` is the nicer type, but exposing it in a public API means
  every consumer inherits whatever opt-in and stability status it carries in
  their Kotlin version. Forcing an `@OptIn` on users of a library is a real
  adoption cost paid for cosmetics. `Long` has no dependency, no opt-in, and is
  what `TimeSource` and the virtual test clock use internally anyway.

  If `Instant` is wanted later it can be added as extension properties without
  a breaking change — the reverse is not true, which is the decisive asymmetry.

## Definition of done

- [ ] `QueryState`, `QueryStatus`, `FetchStatus` implemented.
- [ ] A test for **each reachable combination** of the two axes, including
      `success`+`fetching` and `pending`+`paused`, asserting the derived flags.
- [ ] Test: `isLoading` is false for a disabled query with no data, while
      `isPending` is true — the regression test for the spinner bug.
- [ ] Test: `enabled = false` ignores `invalidateQueries`.
- [ ] Test: data is retained when a background refetch fails.
- [ ] `toUiState()` implemented with tests covering the lossy mappings.
