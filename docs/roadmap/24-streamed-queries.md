# 24 — Streamed Queries

| | |
|---|---|
| **Tier** | 4 — post-v1 |
| **Status** | deferred |
| **Module** | `kwery-core` (extension) |
| **TanStack source** | [`reference/streamedQuery.md`](../../.reference/tanstack-query/docs/reference/streamedQuery.md) |

## TanStack behaviour

`streamedQuery` adapts an async iterable into a query, accumulating chunks into
the query's data as they arrive rather than waiting for a complete response. Its
motivating use case is LLM token streaming; server-sent events and chunked
responses fit the same shape.

## Why this is interesting for Kwery

The impedance mismatch runs the *other* way here. TanStack needed a special
adapter because its `queryFn` returns a single promise and JS async iterables
are awkward. In Kotlin, a streaming source **is already a `Flow`**, and Kwery's
output is already a `Flow` — so a streamed query is closer to a plumbing
question than a new subsystem:

```kotlin
// sketch, not designed
client.streamedQuery(ChatKey(id)) { emitAll(api.streamChat(id)) }
```

The real design questions are about the cache, not the stream:

- **Accumulation.** How do chunks combine — append to a list, or a user-supplied
  reducer? A reducer is more general and probably right.
- **Persistence.** Is a partially-received stream persisted? Almost certainly
  not; resuming a half-finished LLM response from disk is meaningless.
- **Staleness.** What does `staleTime` mean for something still arriving?
  Probably: staleness applies only once the stream completes.
- **Retries.** Resuming a partially consumed stream generally requires
  server-side support (a resume token), so a retry likely restarts from scratch
  — which must be explicit, not accidental.
- **Deduplication.** Two observers of one streaming key must share one stream,
  which the existing `Deferred` memoisation in
  [05](05-deduplication-observers.md) does not cover — a `SharedFlow` with
  replay is needed instead.

That last point is the one with v1 implications: the observer design should not
foreclose sharing a multi-emission source.

## v1 obligation

- [ ] Confirm the [05](05-deduplication-observers.md) observer design can be
      extended to a replaying shared source later, without breaking changes.

## Open questions (post-v1)

- **OQ-1.** Is this in scope for a *server-state cache* at all, or does it belong
  in the application layer? A streaming chat response is arguably not cacheable
  server state. Worth resisting scope creep — the argument for including it is
  mostly that TanStack did.
- **OQ-2.** If built, does it share `QueryState` or need its own state type
  carrying stream completion? Reusing `QueryState` with an `isStreaming` flag is
  tempting but may repeat the mistake AD-4 exists to avoid.
