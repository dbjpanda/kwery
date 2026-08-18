# Inspecting the cache

## The problem

A cache is invisible. When a screen refetches and you did not expect it to, or
data is older than it should be, there is nothing to look at.

Kwery exposes two things for this: a point-in-time view of every entry, and a
stream of transitions with the reason for each.

## What is in the cache right now

```kotlin
client.cacheSnapshot().forEach { entry ->
    println("${entry.key} ${entry.status} stale=${entry.isStale} observers=${entry.observerCount}")
}
```

Each snapshot carries the key, both status axes, `dataUpdatedAt`, `isStale`,
`isInvalidated`, `observerCount` and `observedSinceMillis`. It is immutable, so
nothing you do with it can affect the cache.

## Why did that refetch?

```kotlin
scope.launch {
    client.events.collect { event ->
        when (event) {
            is QueryEvent.FetchStarted -> Log.d("kwery", "${event.key} <- ${event.reason}")
            is QueryEvent.Evicted -> Log.d("kwery", "${event.key} gone: ${event.reason}")
            else -> Unit
        }
    }
}
```

Every fetch says why it started:

| Reason | Means |
|---|---|
| `Mount` | a screen started observing something missing or stale |
| `Invalidated` | `invalidateQueries` marked it stale while something watched |
| `Manual` | `refetchQueries`, or a refetch on the query |
| `FocusRegained` | the app came back to the foreground |
| `Reconnected` | connectivity returned |
| `Interval` | a `refetchInterval` tick |
| `Prefetch` | `prefetchQuery`, `fetchQuery` or `ensureQueryData` |

And every eviction says why it left: `GarbageCollected`, `OverCapacity` or
`Removed`.

The reason is recorded when the transition happens. It cannot be worked out
afterwards from state, which is why it is in the core rather than in a tool.

## Other events

`FetchSucceeded` carries how long it took. `FetchFailed` carries the error and
the attempt count, so a retry storm is visible rather than inferred. `Paused`
and `Resumed` bracket a wait for connectivity. `Invalidated` says whether it
triggered a refetch. `DataSet` fires on a manual write or a hydration.
`ObserverAttached` and `ObserverDetached` carry the new count.

## What goes wrong

**Do not build behaviour on `events`.** It is diagnostics. The buffer holds 256
events and drops the oldest under pressure, because a slow collector must never
stall the cache. If you need to react to data, collect the query.

**There is no replay.** Collect before the work you want to see.

**Keep it out of release builds.** Logging every transition is noise in
production and a small cost for nothing.

## Related

- [Deduplication](deduplication.md) for what `observerCount` means
- [Caching](caching.md) · [Refetching](refetching.md)
