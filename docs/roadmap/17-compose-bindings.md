# 17 — Compose Bindings

| | |
|---|---|
| **Tier** | 3 — v1 integration |
| **Status** | **gate 2 complete** (render tests need a device; `rememberIsMutating` blocked on core) |
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

- [x] `rememberQuery`, `rememberQuerySelecting`, `rememberMutation`,
      `rememberInfiniteQuery` implemented.
- [x] `LocalQueryClient`, `rememberIsFetching`, `rememberIsRestoring`.
- [x] Test: `rememberIsFetching` counts in-flight queries and returns to zero;
      `rememberIsRestoring` follows the client. **Both verified by mutation.**
- [x] `rememberIsMutating` — was blocked on the core having no mutation count.
      [11](11-mutations.md) now has `QueryClient.isMutating`, so the binding
      exists and is tested. Recorded here because the block was real and the
      order it forced — core first, adapter second — is AD-2 working.
- [x] Test: changing the key resubscribes. **Verified by mutation** — dropping
      `key` from the `remember` scope fails it.
- [x] Test: recomposition with an equal key does not.
- [x] Test: a recomposition storm costs exactly one request, even with no grace
      window and `refetchOnMount = Always`. See the note below — this is a real
      guarantee that **no mutation can break**, which is itself the finding.
- [x] Test: the *latest* fetcher lambda is the one that runs, even though a new
      lambda never resubscribes. **Verified by mutation** — replacing
      `rememberUpdatedState` with `remember { fetcher }` fails it.
- [x] Test: leaving composition detaches the observer — a disposed composition
      issues no further requests across grace window and `gcTime`.
- [x] Test: re-entering within the grace window does not refetch (rotation).
- [x] Test: rotation — dispose and immediately remount — causes no refetch.
- [x] Test: the `QueryState` reaching the composable carries the loaded data.
- [x] Confirmed no behaviour lives here that is absent from `kwery-core`
      (AD-2). The bindings are `remember` + `collectAsState` and nothing else.

- [x] Documented in [`docs/compose.md`](../compose.md).

### Requires a device

- [ ] Compose UI tests for the loading / error / refreshing **render** paths.
      These need a real UI, not a headless composition, and are tracked here
      rather than holding the gate open indefinitely.

### An unstable fetcher lambda cannot cause a redundant request

`rememberUpdatedState` exists so that a fetcher reallocated on every
recomposition is not treated as a resubscription trigger. The obvious test —
recompose repeatedly, assert one request — passes, and **keeps passing when the
guard is removed**. Four escalating attempts to make it fail all survived:

| Attempt | Result |
|---|---|
| Capture a `MutableState` in the lambda | Compose memoises the lambda; it is never reallocated |
| Capture a changing `String` local | Lambda genuinely reallocated; still one request |
| `gracePeriod = ZERO` | Still one request |
| `refetchOnMount = Always` as well | Still one request |

The reason is the observer model. A resubscription disposes the old collector
and starts a new one, but cancellation completes asynchronously — the new
collector attaches **before** the old one detaches, so the observer count never
reaches zero and the reattach is never a mount. Under approach C′ a spurious
resubscription costs allocations and observer churn, but it cannot cost a
request.

So the guard is still right, and one half of it *is* load-bearing and proven:
without `rememberUpdatedState` the query keeps running the **first**
composition's closure for ever, which the `latest fetcher` test catches. The
"does not restart the query" half is unfalsifiable here, and the test is kept
as a guarantee lock with that stated plainly rather than as evidence.

This is the same discipline as the polling-loop leak in
[07](07-refetch-triggers.md): say what a request count can and cannot see.
