# 07 — Refetch Triggers

| | |
|---|---|
| **Tier** | 1 — v1 core (irreducible) |
| **Status** | planned |
| **Module** | `kwery-core` (contracts), `kwery-android` (implementations) |
| **TanStack source** | [`guides/window-focus-refetching.md`](../../.reference/tanstack-query/docs/framework/react/guides/window-focus-refetching.md), [`guides/polling.md`](../../.reference/tanstack-query/docs/framework/react/guides/polling.md), [`reference/focusManager.md`](../../.reference/tanstack-query/docs/reference/focusManager.md) |
| **Decision** | AD-1 (JVM-pure core) |

The four moments a stale query refetches on its own. This is the feature that
makes the library feel alive rather than like a cache with extra steps.

## TanStack behaviour

| Trigger | Option | Default |
|---|---|---|
| A new observer mounts | `refetchOnMount` | `true` |
| The window regains focus | `refetchOnWindowFocus` | `true` |
| The network reconnects | `refetchOnReconnect` | `true` (`false` in `networkMode: 'always'`) |
| A timer elapses | `refetchInterval` | off |

Each of the first three accepts `true`, `false`, or `"always"`. `"always"`
refetches regardless of staleness — and is blocked by `staleTime: 'static'`.

`refetchInterval` is independent of `staleTime` and may be a function of the
query, enabling adaptive polling (poll fast while errored, slow when healthy).
`refetchIntervalInBackground` keeps polling when unfocused; without it, polling
pauses.

Focus and connectivity are abstracted behind `focusManager` and `onlineManager`,
which are replaceable — this is exactly how React Native swaps browser events
for `AppState`.

## Kwery design

Per AD-1, the core defines the contracts and `kwery-android` implements them.

```kotlin
// kwery-core
interface FocusManager {
    val isFocused: StateFlow<Boolean>
    companion object { val AlwaysFocused: FocusManager }   // JVM default
}

interface OnlineManager {
    val isOnline: StateFlow<Boolean>
    companion object { val AlwaysOnline: OnlineManager }   // JVM default
}
```

```kotlin
// kwery-android
fun AndroidFocusManager(lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle): FocusManager
fun AndroidOnlineManager(context: Context): OnlineManager
```

Modelling both as `StateFlow<Boolean>` rather than TanStack's listener/callback
pattern means the refetch triggers compose as ordinary Flow operators:

```kotlin
focusManager.isFocused
    .filter { it }                 // rising edge only
    .onEach { refetchStaleActiveQueries() }
```

### Android specifics worth calling out

"Window focus" has no exact Android analogue. Kwery maps it to
**`ProcessLifecycleOwner` `ON_START`/`ON_STOP`** — the app moving between
foreground and background — rather than per-Activity focus. Per-Activity focus
would fire on dialogs, permission prompts, and the app switcher, causing refetch
storms.

`AndroidOnlineManager` uses `ConnectivityManager.registerDefaultNetworkCallback`
and requires `ACCESS_NETWORK_STATE`. Critically, it reports
`NET_CAPABILITY_VALIDATED`, not merely "a network exists" — captive portals and
connected-but-dead Wi-Fi otherwise report online and every request fails. This
is a real, common Android bug that a web-derived design would not anticipate.

### Polling

```kotlin
client.query(
    key = StatusKey,
    refetchInterval = { state ->
        if (state.isError) 5.seconds else 30.seconds
    },
    refetchIntervalInBackground = false,
)
```

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `refetchOnMount` | `true` \| `false` \| `"always"` | `RefetchOn` enum | planned |
| `refetchOnWindowFocus` | as above | as above, foreground events | planned |
| `refetchOnReconnect` | as above | as above | planned |
| `"always"` ignores staleness | yes | yes | planned |
| `"always"` blocked by `staleTime: 'static'` | yes | yes | planned |
| `refetchInterval` constant | yes | yes | planned |
| `refetchInterval` as a function of state | yes | yes | planned |
| `refetchIntervalInBackground` | yes | yes | planned |
| Polling pauses when unfocused | yes | yes | planned |
| Replaceable focus manager | `focusManager` | `FocusManager` interface | planned |
| Replaceable online manager | `onlineManager` | `OnlineManager` interface | planned |
| Only **active** queries refetch on focus | yes | yes | planned |
| Connectivity requires validated network | n/a | yes | divergent (better) |
| Brief app switch does not refetch | no — refetches every time | grace-window suppression | divergent (better) |
| Data Saver suppresses polling/prefetch only | n/a | yes, on by default | divergent (addition) |

## Deliberate divergences

1. **Process lifecycle, not Activity focus.** Documented explicitly, since
   developers coming from TanStack will expect "window focus" semantics.
2. **`StateFlow` instead of listeners.** Makes triggers composable and trivially
   testable with `MutableStateFlow`.
3. **Validated-network requirement.** Prevents captive-portal false positives.

## Open questions

- **OQ-1.** ~~Should focus-triggered refetches be throttled?~~ **Closed by
  [05](05-deduplication-observers.md) OQ-4: no separate throttle.** The grace
  window already suppresses refetch-on-reattach, and it now applies to focus
  refetches too. A brief app switch lands inside the grace window and refetches
  nothing; a genuine return after minutes refetches normally. This reuses one
  concept instead of introducing a second timing knob that would have to be
  reasoned about alongside `staleTime` and `gcTime`.

- **OQ-2.** ~~Honour Android's Data Saver?~~ **Closed: yes, for speculative
  traffic only — shipping in v1, on by default.**

  When the user has explicitly asked the OS to restrict background data, an app
  that keeps polling is ignoring a direct instruction. But suppressing a refetch
  that a visible screen is waiting on makes the app look broken, and the user did
  not ask for that.

  The split is therefore by *who is waiting*:

  | Traffic | Under Data Saver |
  |---|---|
  | Query a visible screen is observing | **always runs** |
  | `refetchInterval` polling | suppressed |
  | Prefetching ([20](20-prefetching.md)) | suppressed |

  Read via `ConnectivityManager.restrictBackgroundStatus` in `kwery-android`,
  with a client-level opt-out for apps that genuinely need to poll regardless.

## Definition of done

- [ ] `FocusManager` / `OnlineManager` contracts with JVM defaults.
- [ ] `AndroidFocusManager` / `AndroidOnlineManager` implemented.
- [ ] Test: stale active query refetches on focus regain; fresh one does not.
- [ ] Test: **inactive** queries do not refetch on focus.
- [ ] Test: `"always"` refetches a fresh query, but not under `StaleTime.Static`.
- [ ] Test: adaptive `refetchInterval` reads current state each tick.
- [ ] Test: polling pauses when unfocused unless
      `refetchIntervalInBackground = true`.
- [ ] Instrumentation test: captive-portal-style network (connected, not
      validated) reports offline.
