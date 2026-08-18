# Caching

## The problem

Two questions get confused constantly, and conflating them is the most common
misunderstanding of this kind of library:

- **When should I refetch data I already have?**
- **When should I forget data nobody is looking at?**

They are different questions with different answers, so Kwery gives them
different settings. `staleTime` governs refetching. `gcTime` governs forgetting.

## The simplest thing that works

```kotlin
client.query(
    key = TodoListKey,
    options = QueryOptions(
        staleTime = StaleTime.of(30.seconds),  // fresh for 30s
        gcTime = 10.minutes,                   // kept 10min after nobody watches
    ),
) { api.todos() }
```

| | Default | Governs |
|---|---|---|
| `staleTime` | `Zero` — stale immediately | whether a refetch happens |
| `gcTime` | 5 minutes | when an unwatched entry is dropped |

## staleTime

While data is **fresh**, nothing refetches it — not a new screen observing it,
not returning to the app, not reconnecting. Once **stale**, any of those
triggers a background refresh while the existing data stays on screen.

The default is `Zero`, by design: data is stale the moment it arrives,
so every new observer refreshes. That is a safe default and a chatty one. Most
apps should raise it:

```kotlin
QueryOptions(staleTime = StaleTime.of(30.seconds))
```

Two special values, and the difference between them is easy to miss:

| | Refetches on staleness | Responds to `invalidateQueries` |
|---|---|---|
| `StaleTime.Infinite` | no | **yes** |
| `StaleTime.Static` | no | **no** |

Use `Static` only for data that genuinely cannot change while the app runs —
feature flags read at boot, permissions loaded at login. Use `Infinite` when you
still want to be able to invalidate it after a write.

## gcTime

An entry with no observers is *inactive*. It stays cached — that is what makes
going back to a screen instant — until `gcTime` elapses, then it is dropped.

An entry someone is watching is **never** garbage collected, whatever `gcTime`
says.

`gcTime` is expressible as `Duration.INFINITE`. Web query libraries typically cap it at around 24
days because `setTimeout` overflows; `delay` takes a `Long`, so "never forget
this" says what it means.

## What goes wrong

**Rotation does not refetch, and that is deliberate.** When a screen is
destroyed and immediately recreated, the observer detaches and reattaches within
a **grace window** (5 seconds by default). A reattach inside that window counts
as the same mount, so it skips the staleness check. Without this, the default
`staleTime = Zero` would fire a redundant request on every rotation.

The same window means a brief app switch — a notification, checking something —
does not refetch either.

**Leaving and returning quickly joins the in-flight request.** If you navigate
away mid-request and come back within the grace window, the original request is
still running and you get its result. You do not wait for a second one.

**Eviction is `gracePeriod + gcTime`, not `gcTime`.** The grace window runs
first. With defaults that is 5 seconds plus 5 minutes.

**The cache is bounded by count as well as by time.** `maxEntries` (500 by
default) evicts least-recently-used *inactive* entries. `gcTime` bounds the
cache by age and nothing else would bound it by size — a browser tab gets
reloaded, but an Android process can live for days.

**A backwards clock jump makes data stale, not eternally fresh.** Staleness
compares wall-clock timestamps, and an NTP correction can move the clock
backwards. Rather than treating the data as fresh until the clock catches up —
potentially hours — a negative elapsed time is treated as elapsed.

**`gcTime` must be at least your persistence `maxAge`.** If you persist the
cache, entries evicted from memory before the stored copy expires mean the
snapshot is written constantly and read almost never. Kwery throws at startup
rather than letting it silently do nothing.

## Related

- [Query state](query-state.md) — what fresh, stale and refetching look like
- [Invalidation](invalidation.md) — marking data stale on demand
- [Persistence](persistence.md) — and the `gcTime` constraint it imposes
