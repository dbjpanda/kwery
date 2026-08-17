# 22 — Devtools

| | |
|---|---|
| **Tier** | 4 — post-v1 |
| **Status** | deferred |
| **Module** | `kwery-devtools` |
| **TanStack source** | [`framework/react/devtools.md`](../../.reference/tanstack-query/docs/framework/react/devtools.md) |

Deferred from v1 deliberately. Devtools are a large surface with no correctness
value, and shipping them before the core is stable means rebuilding them.

## Why they matter anyway

TanStack's devtools are a significant part of why the library is loved rather
than merely used. Cache state is otherwise invisible, and "why did this refetch?"
is the question every user eventually asks. Any Kwery answer must be planned for
even while deferred, because it constrains the core: **the cache must be
inspectable and its transitions observable from day one**, or devtools become
impossible to add without breaking changes.

## Concrete v1 obligation

Ship the inspection surface in `kwery-core` now; build the UI later.

```kotlin
val QueryClient.cacheSnapshot: List<QueryEntrySnapshot>   // keys, status, timestamps, observers
val QueryClient.events: SharedFlow<QueryEvent>            // fetch started/settled, invalidated,
                                                          //  evicted, paused/resumed, and *why*
```

`QueryEvent` carrying the **reason** for each transition is the part that must
exist from the start — reconstructing "this refetched because the app
foregrounded and `staleTime` had elapsed" after the fact is impossible if the
reason was never recorded.

## Options for the UI (post-v1)

1. **In-app Compose overlay** — a debug-build-only panel. Zero setup, works on
   device, but competes for screen space on a phone.
2. **Android Studio / IntelliJ plugin over ADB** — best ergonomics, matches the
   browser-devtools experience, most work by far.
3. **Web UI over an ADB-forwarded local server** — reuses TanStack's proven
   layout, moderate effort, and lets a desktop browser do the rendering.

Leaning 1 for a first release (cheap, useful, no tooling install) with 3 as the
serious follow-up.

## Definition of done (v1 scope only)

- [ ] `QueryEntrySnapshot` and `cacheSnapshot` exposed.
- [ ] `QueryEvent` emitted for every state transition, **including a reason**.
- [ ] Test: an event stream is emitted for a full fetch → stale → refetch →
      evict lifecycle, with correct reasons.
- [ ] Confirm the surface is sufficient to build any of the three UI options
      without further core changes.
