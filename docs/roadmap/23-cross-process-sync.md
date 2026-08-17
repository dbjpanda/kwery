# 23 — Cross-Process Cache Sync

| | |
|---|---|
| **Tier** | 4 — post-v1 |
| **Status** | deferred |
| **Module** | `kwery-sync` (hypothetical) |
| **TanStack source** | [`plugins/broadcastQueryClient.md`](../../.reference/tanstack-query/docs/framework/react/plugins/broadcastQueryClient.md) |

## TanStack behaviour

`broadcastQueryClient` synchronises cache state across browser tabs via
`BroadcastChannel`, so invalidating a query in one tab updates the others.

## Android equivalence

The direct analogue is **multi-process apps** — an app declaring
`android:process` for a service or widget provider gets a second process with a
completely separate `QueryClient` and no shared memory. Related cases:

- A **home-screen widget** in its own process wanting the same cached data.
- A **background sync service** whose writes should invalidate the UI process's
  cache.
- **WorkManager** jobs, which may or may not share the main process.

This is genuinely narrower than the browser case: multi-tab is the *normal* web
situation, whereas most Android apps are single-process. Hence deferral.

## Why it is deferred rather than rejected

The cases above are real, and widgets in particular are common. But the design
is substantial — cross-process invalidation needs a `ContentProvider` or bound
service, serialization of events, and careful thought about which process owns
the persisted cache. Doing it badly is worse than not doing it.

Note the overlap with [15](15-persistence.md): if both processes share a
persisted cache, some synchronisation is implied whether or not it is designed.
**That interaction needs an answer in v1 even though the feature is deferred** —
at minimum, concurrent writes from two processes must not corrupt the persisted
store.

## v1 obligation

- [ ] Document that `QueryClient` is **single-process** and two processes hold
      independent caches.
- [ ] Ensure the persistence layer is safe against concurrent multi-process
      access — either by file locking, or by documenting that a second process
      must not use the same persister instance.
- [ ] Test: two persister instances writing concurrently do not corrupt the
      store (they may lose updates; corruption is unacceptable).

## Open questions (post-v1)

- **OQ-1.** Is `ContentProvider`-based invalidation broadcast, or a shared
  SQLite store with change notifications, the better foundation?
- **OQ-2.** Is there demand? Gate the work on actual issue reports rather than
  building speculatively.
