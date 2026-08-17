# 02 — Query Functions

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned |
| **Module** | `kwery-core` |
| **TanStack source** | [`guides/query-functions.md`](../../.reference/tanstack-query/docs/framework/react/guides/query-functions.md), [`guides/default-query-function.md`](../../.reference/tanstack-query/docs/framework/react/guides/default-query-function.md) |
| **Blocks** | 03 Query state, 10 Cancellation |

The query function is the unit of work: given a key, produce data or fail.

## TanStack behaviour

- `queryFn` returns a promise that either resolves data or **throws**. Returning
  `undefined` is treated as an error, because it is indistinguishable from a
  forgotten `return`.
- The function receives a context containing `queryKey`, an `AbortSignal`, and
  (for infinite queries) `pageParam`.
- Errors must be thrown, not returned. Libraries like `axios` throw on non-2xx
  automatically; `fetch` does not, so users must check `response.ok` themselves.
  This is a documented footgun.
- A **default query function** can be registered globally, letting call sites
  supply only a key.

## Kwery design

```kotlin
fun interface QueryFn<T> {
    /** Throws on failure. Cancellation is cooperative via the calling scope. */
    suspend fun QueryFnScope.fetch(): T
}

class QueryFnScope internal constructor(
    val key: QueryKey<*>,
    val signal: QuerySignal,   // for interop with non-suspending clients
)
```

Kotlin removes the two biggest footguns for free:

- **`undefined` is not representable.** A `QueryFn<T>` where `T` is non-nullable
  cannot return "nothing" — it either returns `T` or throws. If a user genuinely
  wants a nullable result they declare `QueryFn<T?>` and opt in explicitly.
- **Cancellation needs no `AbortSignal` threading.** The function is `suspend`,
  so it is cancelled by its coroutine scope, and any well-behaved suspending
  client (Ktor, Retrofit with `suspend`, OkHttp via `await`) already honours it.
  `signal` exists only for bridging blocking or callback-based clients.

Failure remains signalled by exception, matching TanStack. The alternative —
returning `Result<T>` — was rejected because it makes the common path noisier
and interacts badly with the retry engine, which needs to distinguish a thrown
`CancellationException` from a genuine failure.

### Default query function

```kotlin
val client = QueryClient {
    defaultQueryFn { key -> httpClient.get(key.toUrl()).body() }
}
```

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Async function returning data | `queryFn: () => Promise<T>` | `suspend fun fetch(): T` | planned |
| Failure by throwing | yes | yes | planned |
| `undefined` return is an error | runtime check | **impossible to express** | divergent (better) |
| Key available in fn context | `ctx.queryKey` | `QueryFnScope.key` | planned |
| Cancellation signal | `ctx.signal` (`AbortSignal`) | structured concurrency + `QuerySignal` bridge | divergent (better) |
| `pageParam` for infinite queries | `ctx.pageParam` | see [16](16-infinite-queries.md) | planned |
| Global default query function | `defaultOptions.queries.queryFn` | `QueryClient { defaultQueryFn { … } }` | planned |
| `skipToken` to disable type-safely | yes | see [03](03-query-state.md) | planned |

## Deliberate divergences

1. **No `Result<T>` return type.** Exceptions are the contract, as in TanStack.
   Kotlin users may expect `Result`, so this needs to be explicit in the docs.
2. **`AbortSignal` demoted to a bridge.** Structured concurrency covers the
   common case; `QuerySignal` exists only for blocking clients that cannot be
   cancelled cooperatively.

## Open questions

- **OQ-1.** Should `QueryFnScope` be a receiver (`QueryFnScope.() -> T`) or a
  parameter? Receiver reads better at call sites that use `key`, but makes the
  common ignore-the-context lambda marginally more confusing in IDE hints.
- **OQ-2.** Does the default query function survive typed keys? It must map an
  arbitrary `QueryKey<*>` to a request without knowing `T`, which forces an
  unchecked cast somewhere. Possibly the feature is simply a poor fit for
  Kwery's key model and should be dropped rather than made unsafe.

## Definition of done

- [ ] `QueryFn` and `QueryFnScope` implemented.
- [ ] Test: thrown exception propagates to `QueryState.error` after retries.
- [ ] Test: `CancellationException` is **not** treated as a failure and does not
      consume a retry attempt — the single most important correctness test here.
- [ ] Test: cancelling the observing scope cancels an in-flight Ktor request.
- [ ] Test: `QuerySignal` cancels a blocking OkHttp call.
- [ ] Decision recorded on OQ-2, with the default query function either
      implemented safely or documented as an explicit non-goal.
