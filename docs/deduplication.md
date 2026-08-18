# Deduplication and observers

## The problem

Two screens want the same data. A list and a detail view both read the user
profile; a rotation tears down a screen and builds it again a frame later.

Without coordination each of those is a separate request. Deduplication is not
an optimisation here — it is the difference between an app that issues one
request and one that issues five for the same bytes.

## What sharing means

Every collector of a key attaches to **one cache entry**. They share the data,
the status, and the single in-flight request.

```kotlin
// Two screens, one request, one shared state.
val a = client.query(ProfileKey(id)) { api.profile(id) }
val b = client.query(ProfileKey(id)) { api.profile(id) }
```

Ten collectors starting at once produce **one** request. If one of them triggers
a refetch, all ten see `isRefreshing` — that is the point, and it is the part
that surprises people first.

Identity is the key's `equals`, so `ProfileKey("7")` built in two places is one
entry. See [query keys](query-keys.md).

## The lifecycle

An entry moves through three states, driven entirely by how many collectors it
has:

1. **Active** — one or more collectors. Refetch triggers apply, polling runs.
2. **Grace** — the last collector left less than 5 seconds ago. Nothing has
   changed yet.
3. **Inactive** — the grace window elapsed. Polling stops and the `gcTime`
   timer starts. After `gcTime` the entry is evicted.

So the total time from "last screen closed" to "data gone" is
`gracePeriod + gcTime` — 5 seconds plus 5 minutes by default.

## The grace window, and why it exists

A grace window before eviction is unremarkable. The load-bearing part is
different:

> **A reattach inside the grace window is a continuation, not a new mount.**

That means it skips the refetch-on-mount staleness check. Without it, rotation
with the default `staleTime = 0` fires a redundant request *every single time
the device turns* — because refetch-on-mount is driven by staleness, not by
observer accounting, and a grace period alone does not prevent it.

This was settled by a measured spike rather than reasoning, and the measurement
overturned the assumption. Reattaching 50 ms after detaching — comfortably
inside a five-second grace window — still fired a second request, because
refetch-on-mount is driven by staleness, not by observer accounting.

Measured across the scenarios that decided the design:

| Scenario | Result |
|---|---|
| Rotation, direct collection, `staleTime = 0`, grace 5 s | **1 extra request** — the bug |
| Same, with `staleTime = 30 s` | 0 |
| Rotation under `stateIn(WhileSubscribed(5 s))`, `staleTime = 0` | 0 |
| Leave and return inside grace, slow request in flight | 1 request — the in-flight one is **joined**, not restarted |
| Abandon a slow request and never return | cancelled at exactly grace expiry |

The third row is why this is invisible under the ViewModel pattern: the upstream
stays alive, so the cache never sees a detach at all. It shows up immediately in
`rememberQuery`, which collects directly.

The fourth row is the other half of the window's value. Without grace,
navigating away mid-request cancels it and returning a moment later starts over
— wasting the request and making the user wait twice.

**The two timeouts stack harmlessly.** Time from closing a screen to eviction
was 305 000 ms with direct collection, and 310 000 ms with
`WhileSubscribed(5 s)` layered on top: a 1.6 % difference, which is why there is
no custom `SharingStarted` and no third timing knob to reason about.

Tuning it:

```kotlin
QueryClient(scope, QueryClientConfig(gracePeriod = 5.seconds))
```

Five seconds matches the `SharingStarted.WhileSubscribed(5_000)` value Android
developers already use. Setting it to zero makes every reattach a fresh mount.

## What deduplication does not collapse

**Sequential attempts.** Three retries of one query are three requests. Only
*concurrent* observers share.

**Different keys.** `TodoKey("1")` and `TodoKey("2")` are unrelated, and N
observers across N keys issue N concurrent requests — parallelism is the default,
not something to opt into. See [parallel queries](parallel-queries.md).

**Writes.** Mutations are not cached by key at all; each call is its own. What
they share is a [`MutationScope`](mutations.md) lock, if they declare one.

## Inspecting it

```kotlin
client.cacheSnapshot().forEach { entry ->
    println("${entry.key}: ${entry.observerCount} observers, active=${entry.isActive}")
}
```

`QueryEntrySnapshot` also carries `observedSinceMillis` — when the observer
count last rose from zero, or null if nothing is watching. It tracks the *first*
observer, so a second screen opening does not reset it. That is what makes a
collector which never completes visible; see
[ViewModels](viewmodels.md#lazily-leaks).

## What goes wrong

**A collector that never completes never releases the entry.** `gcTime` starts
when the last observer leaves, so a subscription that never ends means an entry
that is never evicted. `SharingStarted.Lazily` is the usual culprit.

**Kwery does not warn about this.** The cache cannot distinguish a leaked
collector from a screen the user has had open all afternoon — both are one
observer that never detaches — and a warning that fires on correct code is worse
than none. The data is exposed instead.

**Cancel your collectors in tests.** One left running changes what every
subsequent time-advance measures.

**The cache is bounded by count as well as time.** `maxEntries` defaults to 500,
and least-recently-used *inactive* entries are evicted above it. An observed
entry is never evicted whatever the pressure. `gcTime` bounds the cache by time
and nothing else would bound it by size — a browser tab gets reloaded, but an
Android process can live for days.

## Related

- [Caching](caching.md) — `staleTime` and `gcTime`
- [Queries](queries.md) · [ViewModels](viewmodels.md) · [Compose](compose.md)
