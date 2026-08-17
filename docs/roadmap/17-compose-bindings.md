# 17 — Compose Bindings

| | |
|---|---|
| **Tier** | 3 — v1 integration |
| **Status** | planned |
| **Module** | `kwery-compose` |
| **TanStack source** | [`reference/useQuery.md`](../../.reference/tanstack-query/docs/framework/react/reference/useQuery.md), [`reference/QueryClientProvider.md`](../../.reference/tanstack-query/docs/framework/react/reference/QueryClientProvider.md) |
| **Depends on** | 05 Observers |
| **Decision** | AD-2 — a thin adapter, never a parallel implementation |

The surface most familiar to TanStack users. AD-2 makes this deliberately thin:
if a behaviour exists only in `kwery-compose`, it is in the wrong module.

## Design

```kotlin
@Composable
fun <T> rememberQuery(
    key: QueryKey<T>,
    enabled: Boolean = true,
    staleTime: StaleTime = StaleTime.of(Duration.ZERO),
    queryFn: QueryFn<T>,
): QueryState<T>
```

```kotlin
@Composable
fun TodoScreen(id: String) {
    val state = rememberQuery(TodoKey(id)) { api.getTodo(id) }

    when {
        state.isLoading -> Spinner()
        state.isError   -> ErrorView(state.error!!, onRetry = { /* … */ })
        else            -> TodoView(state.data!!, refreshing = state.isRefreshing)
    }
}
```

`CompositionLocalProvider` supplies the client, mirroring `QueryClientProvider`:

```kotlin
CompositionLocalProvider(LocalQueryClient provides client) { App() }
```

### Points of care

- **Key identity drives resubscription.** `rememberQuery` must re-subscribe when
  the key changes and not otherwise — so the key is the `remember` key, and
  `QueryKey`'s structural equality ([01](01-query-keys.md)) makes that correct
  without users memoising anything. This is the Compose analogue of React's
  dependency array, and it is handled for them.
- **`queryFn` must not be a resubscription trigger.** A lambda allocated per
  recomposition would otherwise restart the query on every frame. It is captured
  via `rememberUpdatedState`.
- **Observer lifetime** is `DisposableEffect`, which is the one place Compose is
  actually *better* defined than the general Flow case in
  [05](05-deduplication-observers.md) — composition enter/leave is as precise as
  React's mount/unmount.
- **`toUiState()`** from [03](03-query-state.md) is available for consumers who
  prefer an exhaustive `when`.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `useQuery` equivalent | yes | `rememberQuery` | planned |
| `useMutation` equivalent | yes | `rememberMutation` | planned |
| `useInfiniteQuery` equivalent | yes | `rememberInfiniteQuery` | planned |
| `useQueryClient` | yes | `LocalQueryClient.current` | planned |
| `QueryClientProvider` | yes | `CompositionLocalProvider` | planned |
| `useIsFetching` / `useIsMutating` | yes | `rememberIsFetching` / `rememberIsMutating` | planned |
| `useQueries` (dynamic parallel) | yes | see [19](19-dependent-parallel.md) | planned |
| `useMutationState` | yes | `rememberMutationStates` | planned |
| `useIsRestoring` | yes | `rememberIsRestoring` | planned |
| Suspense integration | yes | non-goal — see roadmap | divergent (gap) |
| Error boundary reset | yes | non-goal | divergent (gap) |
| Lazy-list pagination helper | no | `fetchNextPageWhenNearEnd` | divergent (addition) |

## Open questions

- **OQ-1.** Should `rememberQuery` take `QueryOptions` as one parameter rather
  than a long parameter list? A long list is more discoverable in Compose's
  autocomplete; an options object is less noisy and matches the core. Leaning:
  overloads — common params inline, an `options: QueryOptions<T>` escape hatch.
- **OQ-2.** Should there be a `rememberQuery` overload that takes no `queryFn`,
  relying on a registry keyed by `QueryKey` type? It would make screens very
  terse, but hides where the network call actually happens. Probably not.

## Definition of done

- [ ] `rememberQuery`, `rememberMutation`, `rememberInfiniteQuery` implemented.
- [ ] `LocalQueryClient` and the fetching/mutating/restoring helpers.
- [ ] Test: changing the key resubscribes; recomposition with an equal key does
      not.
- [ ] Test: a `queryFn` lambda reallocated each recomposition does **not**
      restart the query.
- [ ] Test: leaving composition detaches the observer; re-entering within the
      grace window does not refetch.
- [ ] Test: rotation with `rememberSaveable`-held keys causes no refetch.
- [ ] Compose UI tests for the loading / error / refreshing render paths.
- [ ] Confirm no behaviour lives here that is absent from `kwery-core` (AD-2).
