# Reading and writing the cache

## The problem

Most of the time the cache manages itself: you declare a query and it fetches,
caches and refreshes. Occasionally you need to reach in — write a server
response you already have, read what is cached without triggering a fetch, or
throw an entry away on logout.

These are the escape hatches. Reaching for them constantly usually means a
query is missing.

## Reading

```kotlin
val todo: Todo? = client.getQueryData(TodoKey("5"))          // data only
val state: QueryState<Todo>? = client.getQueryState(TodoKey("5"))  // the full state
```

Both are **typed by the key** and neither triggers a fetch. `getQueryData`
returns null when there is no entry *and* when the entry holds no data yet —
use `getQueryState` when you need to tell those apart.

## Writing

```kotlin
client.setQueryData(TodoKey("5"), todo)                       // replace
client.setQueryData(TodoKey("5")) { it?.copy(done = true) }   // update from current
client.updateQueryData(TodoKey("5")) { it.copy(done = true) } // only if data exists
```

The lambda form receives the current value — null if there is none — and
returning null clears the data. `updateQueryData` skips the null case entirely,
which is what you want when "there is nothing to update" means "do nothing".

Writing marks the entry **not stale**, because you just told it what the truth
is. It does not mark it fresh forever: `staleTime` still applies from the moment
of the write.

Across several keys at once:

```kotlin
client.setQueriesData<List<Todo>>(QueryFilters(keyPrefix = listOf("todos"))) { current ->
    current?.map { it.copy(done = true) }
}
```

The point of the bulk form is the keys you *do not know*: a paginated inbox has
as many entries as the user has scrolled, and "mark everything read" cannot
enumerate them. It is unchecked by nature — a filter selects by key shape, not
by type — so keep the prefix narrow enough that every match really holds a `T`.
That is a property of how you name keys, which is part of why
[`parts`](query-keys.md) is hierarchical.

The commonest use of the single-key form is writing a mutation's response
straight into the cache so the screen updates without a refetch — see
[mutations](mutations.md).

## Removing

```kotlin
client.removeQueries(QueryFilters(keyPrefix = listOf("todos")))  // evict outright
client.resetQueries(QueryFilters(keyPrefix = listOf("todos")))   // back to initial state
client.removeQueries(QueryFilters.All)                           // logout
```

`removeQueries` evicts and does not refetch. `resetQueries` returns entries to
their initial state, so an observed query starts loading again.

Neither is the same as [invalidation](invalidation.md), which keeps the data on
screen while it refreshes. Removing blanks the screen — appropriate on logout,
wrong for "this might have changed".

## Inspecting

```kotlin
client.cacheSnapshot().forEach { entry ->
    println("${entry.key} status=${entry.status} observers=${entry.observerCount}")
}
```

An immutable view of every entry: status, `dataUpdatedAt`, `isStale`,
`isInvalidated`, `observerCount`, and `observedSinceMillis`. Useful in debug
builds and for spotting a collector that never completes — see
[deduplication](deduplication.md).

## What goes wrong

**A write is not a fetch.** `setQueryData` on a key nothing has queried creates
an entry holding your data with no fetcher attached. When a screen later
observes that key with a fetcher, it adopts it and refetches normally — but
until then nothing will ever refresh it.

**Type safety comes from the key, not the call.** `setQueryData(TodoKey("5"))
{ … }` gets a `Todo?` because `TodoKey` is a `QueryKey<Todo>`. That is the whole
argument for typed keys.

**`removeQueries` while a screen is watching blanks it.** The observer sees an
empty state and, if it has a fetcher, starts loading again. That is usually the
intent on logout and rarely the intent otherwise.

**These run on the client's lock.** They are `suspend` for that reason. Do not
call them in a tight loop from a UI callback; batch with `setQueriesData` or a
single updater instead.

## Related

- [Invalidation](invalidation.md) — the usual tool, which refetches rather than replaces
- [Mutations](mutations.md) · [Optimistic updates](optimistic-updates.md)
- [Caching](caching.md) · [Deduplication](deduplication.md)
