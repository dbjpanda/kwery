# Refetching

## The problem

Cached data goes wrong silently. The user sees a number that was true when the
screen opened, and nothing on screen says otherwise.

Refetch triggers are what make the cache feel alive rather than stale. There are
four, and each maps to a moment where the data plausibly changed while you were
not looking.

| Trigger | Option | Default |
|---|---|---|
| A screen starts observing | `refetchOnMount` | `IfStale` |
| The app returns to the foreground | `refetchOnFocus` | `IfStale` |
| Connectivity returns | `refetchOnReconnect` | `IfStale` |
| A timer elapses | `refetchInterval` | off |

Each of the first three takes a `RefetchOn`: `Never`, `IfStale` (only when
`staleTime` has elapsed), or `Always` (regardless of staleness — but still
refused by `StaleTime.Static`).

## Focus is process lifecycle, not window focus

TanStack refetches on window focus. Android has no equivalent, and the obvious
mapping is wrong.

Kwery uses **`ProcessLifecycleOwner`** — the app moving between foreground and
background. Per-Activity focus would fire on every dialog, permission prompt and
app-switcher glance, which is a refetch storm rather than a feature.

```kotlin
QueryClient(QueryClientConfig(
    focusManager = AndroidFocusManager(),
    onlineManager = AndroidOnlineManager(context),   // needs ACCESS_NETWORK_STATE
))
```

Both are interfaces with JVM defaults (`AlwaysFocused`, `AlwaysOnline`), so the
core stays pure and tests substitute a `MutableStateFlow`.

`AndroidOnlineManager` is `AutoCloseable` and registers a `ConnectivityManager`
callback. Give it application scope — one per process, closed when the process
is done, not one per Activity.

**A brief app switch does not refetch.** Returning within the 5-second grace
window is treated as a continuation, not a new visit. Replying to a
notification and coming straight back refetches nothing; returning after minutes
refetches normally. This reuses the grace window that already exists for
observers rather than adding a second timing knob.

## Reconnect requires a *validated* network

`AndroidOnlineManager` reports online only when the network has
`NET_CAPABILITY_VALIDATED` — not merely when one exists.

This matters more than it sounds. A hotel captive portal, or Wi-Fi that has
dropped its uplink, reports a perfectly good network on which every request
fails. Treating "connected" as "online" means refetching into a black hole and
showing errors instead of the paused state the user should see.

## Polling

```kotlin
client.query(
    key = JobKey(id),
    options = QueryOptions(
        refetchInterval = { state ->
            if (state.data?.isRunning == true) 2.seconds else 30.seconds
        },
    ),
) { api.job(id) }
```

The lambda is **re-read after every tick**, so the interval adapts without
restarting the query — poll fast while a job runs, slow down once it finishes.
Returning `null` stops the loop, and takes effect immediately rather than after
one more already-scheduled tick.

Polling is independent of `staleTime`: a poll refetches whether or not the data
is considered stale, because polling exists to detect *server-side* change the
client cannot predict.

**Polling pauses when the app is backgrounded** and resumes on return, without
needing a reattach. `refetchIntervalInBackground = true` opts out — but consider
whether you want it: polling for a screen nobody is looking at spends battery
and cellular data, and Android will freeze the process anyway.

The loop also stops when the last observer leaves. That is not the same as
suppressing its requests — a loop that keeps waking every second to decide not
to fetch is a leak that no request count can see. It was caught by counting
interval evaluations: 129 where 9 were expected.

## Manual refetching

```kotlin
client.invalidateQueries("todos")    // mark stale + refetch what is on screen
client.refetchQueries(QueryFilters(keyPrefix = listOf("todos")))  // refetch regardless
```

Invalidation is the usual tool — see [invalidation](invalidation.md).
`refetchQueries` ignores staleness, which is what pull-to-refresh wants.

## What goes wrong

**Only active queries refetch on focus.** A query nothing is observing is marked
stale and refetches when a screen next looks at it — refetching every cached
entry on every foreground would be an enormous, mostly wasted burst.

**`enabled = false` ignores every trigger**, including invalidation.

**`StaleTime.Static` refuses even `Always`.** That is the difference between
`Static` and `Infinite`: `Infinite` never goes stale but can be invalidated;
`Static` refuses automatic refetching outright. Use it for data that genuinely
cannot change — a country list, a completed order.

## Related

- [Caching](caching.md) — what "stale" means
- [Invalidation](invalidation.md) · [Offline](offline.md) · [Queries](queries.md)
