# 02 — Query Functions

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | **gate 2 complete** |
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
client.query(TodoKey(id)) { api.todo(id) }
```

A query function is a plain `suspend () -> T`. **No `QueryFn` or `QueryFnScope`
type was needed** — the design in the original spec, before OQ-1 was closed. The
call site already holds everything a scope would have carried, because the key
was just constructed from the same values, and structured concurrency removes
the need to thread a cancellation token.

Kotlin removes the two biggest footguns for free:

- **`undefined` is not representable.** A `suspend () -> T` with non-nullable
  `T` cannot return "nothing" — it either returns `T` or throws. A genuinely
  nullable result is declared `suspend () -> T?` and opted into explicitly.
- **Cancellation needs no `AbortSignal` threading.** The function is `suspend`,
  so it is cancelled by its coroutine scope, and any well-behaved suspending
  client (Ktor, Retrofit with `suspend`, OkHttp via `await`) already honours it.
  `signal` exists only for bridging blocking or callback-based clients.

Failure remains signalled by exception, matching TanStack. The alternative —
returning `Result<T>` — was rejected because it makes the common path noisier
and interacts badly with the retry engine, which needs to distinguish a thrown
`CancellationException` from a genuine failure.

### No global default query function

TanStack's `defaultQueryFn` is deliberately **not** ported — it cannot be made
type-safe against `QueryKey<T>`. See OQ-2.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| Async function returning data | `queryFn: () => Promise<T>` | `suspend () -> T` | done |
| Failure by throwing | yes | yes | planned |
| `undefined` return is an error | runtime check | **impossible to express** | divergent (better) |
| Key available in fn context | `ctx.queryKey` | not needed — the call site has it | divergent (simpler) |
| Cancellation signal | `ctx.signal` (`AbortSignal`) | structured concurrency | divergent (better) |
| `pageParam` for infinite queries | `ctx.pageParam` | see [16](16-infinite-queries.md) | planned |
| Global default query function | `defaultOptions.queries.queryFn` | **dropped** — cannot be type-safe (OQ-2) | divergent (gap) |
| `skipToken` to disable type-safely | yes | see [03](03-query-state.md) | planned |

## Deliberate divergences

1. **No `Result<T>` return type.** Exceptions are the contract, as in TanStack.
   Kotlin users may expect `Result`, so this needs to be explicit in the docs.
2. **No cancellation token at all.** Structured concurrency covers every
   suspending client, and `suspendCancellableCoroutine` covers callback-based
   ones — now demonstrated by a test using exactly the client shape that would
   otherwise need a `QuerySignal`.

   Writing that test found a nuance worth stating: cancelling one collector does
   **not** abort the request. An in-flight fetch belongs to the entry, not to
   whichever collector started it, so aborting on detach would kill a request a
   rotation is about to want back — and would abort a shared fetch out from
   under a second screen. The abort happens when the grace window closes with
   nothing observing. **Verified by mutation**: aborting on detach fails the
   test.

## Open questions

- **OQ-1.** ~~Receiver or parameter for `QueryFnScope`?~~ **Closed: neither, in
  the common case.** Both options in the original question were worse than the
  obvious third one.

  The typical call site already has everything it needs, because the key was
  just constructed from the same values:

  ```kotlin
  client.query(TodoKey(id)) { api.todo(id) }     // scope never mentioned
  ```

  So the primary overload takes a plain `suspend () -> T` and there is no
  ceremony at all. A second overload takes `suspend (QueryFnScope) -> T` for the
  rare case that needs the key or the cancellation bridge; Kotlin resolves the
  two by lambda arity.

  A receiver is specifically rejected: `this` inside a `QueryFn` written in a
  ViewModel or composable would shadow the enclosing receiver, which is
  confusing in exactly the places people write these lambdas.

- **OQ-2.** ~~Does a global default query function survive typed keys?~~
  **Closed: no. It is dropped, as an explicit non-goal.**

  It cannot be made type-safe: a global function must map an arbitrary
  `QueryKey<*>` to some `T` it cannot know, so every implementation ends in an
  unchecked cast. It exists in TanStack to compensate for stringly-typed keys —
  a problem AD-3 already solves, since a typed key names its own data type and
  its fetcher naturally lives beside it.

  Shipping an unsafe convenience to claim a parity checkbox is the wrong trade
  for a library others depend on. Recorded as a deliberate gap in the parity
  table with this reason.

  (A `SelfFetchingQueryKey<T>` carrying its own `fetch()` would be type-safe and
  terser than TanStack, but it couples key identity to the network layer — a
  design opinion better validated by users than assumed. Not in v1.)

## Definition of done

- [x] Query functions are plain `suspend () -> T` (see OQ-1); no separate
      `QueryFn`/`QueryFnScope` types were needed.
- [x] Test: the thrown exception reaches `QueryState.error` **unchanged** —
      the caller's own instance, not a stacktrace-recovered copy.
- [x] Test: `CancellationException` is **not** treated as a failure and does not
      consume a retry attempt. **Verified by mutation**: without the guard,
      `RetryPolicy.Forever` turns one cancellation into 251 attempts.
- [x] Test against a **callback-based** client — the shape that would need a
      cancellation token. Cancelling the query aborts it through
      `suspendCancellableCoroutine`, and a completed request is not reported as
      aborted. This is the divergence's actual claim, tested rather than
      asserted; a Ktor/OkHttp integration source set would add network flakiness
      to prove the same thing about coroutine-native clients.
- [ ] ~~`QuerySignal` bridge for non-cancellable clients.~~ **Not built, and now
      backed by a test rather than a belief.** The bridge is fifteen lines of
      standard library at the call site, and it is the caller's fifteen lines
      rather than a parameter every query function carries for ever.
- [x] OQ-2 closed: the global default query function is an explicit non-goal.
