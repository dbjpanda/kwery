# 13 — Network Mode & Offline Pause

| | |
|---|---|
| **Tier** | 2 — v1 headline |
| **Status** | planned |
| **Module** | `kwery-core`, `kwery-android` |
| **TanStack source** | [`guides/network-mode.md`](../../.reference/tanstack-query/docs/framework/react/guides/network-mode.md), [`reference/onlineManager.md`](../../.reference/tanstack-query/docs/reference/onlineManager.md) |
| **Depends on** | 03 Query state, 07 Refetch triggers |
| **Blocks** | 14 Offline queue |

Why `fetchStatus` exists at all. On Android this is not an edge case — it is
Tuesday.

## TanStack behaviour

Three modes, per query or globally, defaulting to `online`:

**`online`** (default) — queries do not fire without a connection. The query
keeps its current `status` and reports `fetchStatus: 'paused'`. If connectivity
drops *mid-flight*, the **retry mechanism pauses** too, and resumes on reconnect
as a *continue*, not a refetch — independent of `refetchOnReconnect`. A query
cancelled in the meantime does not continue.

**`always`** — ignores connectivity entirely. Never pauses, retries never pause,
failures go straight to `error`. `refetchOnReconnect` defaults to `false` here,
since reconnection no longer implies staleness. For query functions that do not
need the network (reading local storage, returning constants).

**`offlineFirst`** — runs the query function **once**, then pauses retries. For
setups where the first attempt may be served by an HTTP cache or interceptor; on
a cache miss the request fails and it behaves like `online`.

## Kwery design

```kotlin
enum class NetworkMode { Online, Always, OfflineFirst }
```

The state machine, which is the substance of this feature:

```
                    ┌──────────────── online ────────────────┐
                    ▼                                        │
  Idle ──fetch requested──> [online?] ──yes──> Fetching ──success──> Idle
                               │                   │
                               no                  │ failure
                               ▼                   ▼
                            Paused <──offline── [retry allowed?]
                               │                   │ yes & online
                               │                   ▼
                               └──reconnect──> Fetching (continue)
```

The subtle requirement, easy to miss: resuming from `Paused` **continues the
retry sequence** — it does not restart the fetch from attempt zero, and it does
not count as a refetch. A query paused on retry attempt 2 resumes at attempt 3
with the correct backoff.

`OnlineManager` (from [07](07-refetch-triggers.md)) supplies connectivity, and
`AndroidOnlineManager` requires a **validated** network so captive portals do
not report online.

### Android-specific defaults worth reconsidering

TanStack defaults to `online` because browsers have reliable `navigator.onLine`.
Android connectivity is far noisier — brief cell handovers, doze, VPN
transitions. `Online` remains Kwery's default for parity, but two things follow:

- Pausing must be **fast to recover from**: a 200 ms connectivity blip should
  not visibly stall a request. Resume is driven by the `StateFlow` edge, so it
  is immediate.
- The docs must steer `always` for query functions backed by Room or DataStore,
  where connectivity is irrelevant and the default would wrongly pause them.

## Parity table

| Capability | TanStack | Kwery | Status |
|---|---|---|---|
| `networkMode: online` default | yes | `NetworkMode.Online` | planned |
| Paused when offline, status preserved | yes | yes | planned |
| `fetchStatus: paused` surfaced | yes | `FetchStatus.Paused` | planned |
| `isPaused` derived flag | yes | yes | planned |
| Retries pause mid-sequence when offline | yes | yes | planned |
| Resume **continues** retry sequence | yes | yes | planned |
| Cancelled paused query does not continue | yes | yes | planned |
| `networkMode: always` | yes | `NetworkMode.Always` | planned |
| `always` ⇒ `refetchOnReconnect` false by default | yes | yes | planned |
| `offlineFirst` runs fn once then pauses retries | yes | `NetworkMode.OfflineFirst` | planned |
| Per-query and global setting | yes | yes | planned |
| Replaceable online manager | yes | `OnlineManager` | planned |
| Mock-offline toggle for testing | devtools | `TestOnlineManager` in `kwery-test` | planned |
| Validated-network requirement | n/a | yes | divergent (better) |

## Open questions

- **OQ-1.** Should Kwery distinguish metered from unmetered connections, letting
  a query declare "only refetch on unmetered"? No TanStack analogue, genuinely
  useful on Android for large payloads, but expands the API. Post-v1 candidate.
- **OQ-2.** Should a brief connectivity blip debounce before pausing? Pausing on
  a 100 ms drop and resuming immediately produces state churn observers see.
  Leaning: debounce the *offline* transition by a short window (~500 ms), do not
  debounce the *online* transition. Needs a default decision and a test.

## Definition of done

- [ ] `NetworkMode` implemented across queries and mutations.
- [ ] Test: offline query reports `pending` + `paused`, not `error`.
- [ ] Test: cached data + offline reports `success` + `paused`.
- [ ] Test: going offline mid-retry pauses; reconnect resumes at the **next**
      attempt index with correct backoff, and is not counted as a refetch.
- [ ] Test: a paused query cancelled before reconnect does not resume.
- [ ] Test: `Always` never pauses and errors normally while offline.
- [ ] Test: `OfflineFirst` runs the fn once offline, then pauses retries.
- [ ] Test: `Always` defaults `refetchOnReconnect` to false.
- [ ] `TestOnlineManager` shipped in `kwery-test` for consumer tests.
