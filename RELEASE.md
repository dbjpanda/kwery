# What is and is not built

The inventory behind the headline in [README.md](README.md). That says what
Kwery is and how to install it; this says what to expect from it, and what was
left out on purpose.

Every claim here is traceable to a test or a `./gradlew build apiCheck` run,
because it was written by checking the repository rather than remembering it —
which is how it turned up a blocker nothing had flagged: for a while there was
no publishing configuration at all, so nothing could be consumed by anyone.

## Module maturity

| Module | Purpose | State |
|---|---|---|
| `kwery-core` | cache, observers, retries, mutations, infinite queries | complete for v1 |
| `kwery-test` | `TestQueryClient`, virtual clock, request recording | complete for v1 |
| `kwery-persist` | persistence contracts, file stores, offline queue | complete for v1 |
| `kwery-persist-room` | Room-backed cache and queue, for large or busy caches | complete for v1 |
| `kwery-android` | `FocusManager`, `OnlineManager` | complete for v1 |
| `kwery-compose` | `rememberQuery` and friends | complete for v1 |
| `docs-lint` | holds the documentation to the published API | not published |

## How big a cache can it persist?

This was written as a blocker on the assumption that restoring a large cache
from a file would be too slow. Measured, with entries the size of a typical list
row:

| Entries | File | Write | Restore |
|---|---|---|---|
| 100 | 11 KiB | <1 ms | <1 ms |
| 1 000 | 119 KiB | 1 ms | <1 ms |
| 10 000 | 1.2 MiB | 4–7 ms | 3–5 ms |

Ten thousand entries restore in single-digit milliseconds. Allow an order of
magnitude for slower storage and a cold JIT and it is still not a startup
problem.

What the benchmark *did* find is **write amplification**: a one-entry change
rewrites the whole file, so at 10 000 entries every change costs 1.2 MiB of
flash. That is battery and flash wear, not correctness, and no functional test
would ever notice it. It is bounded in practice because the persist loop skips
writing when nothing has changed — a bug found the same way, by counting writes
rather than checking results.

So a Room-backed store is **not** a v1 blocker, and the case for it is row-level
updates rather than startup latency. If your app holds a very large cache and
changes it continuously, persist a smaller subset with `exclude` rather than
everything.

## Needs a device

Run on an emulator since this environment cannot run instrumentation tests
directly:

| Area | Test | State |
|---|---|---|
| Query keys | R8-shrunk build proving canonical key strings survive minification | done |
| Refetch triggers | Real `ConnectivityManager` agrees with `AndroidOnlineManager` on VALIDATED state | done |
| Offline mutation queue | Queue and cache round-trip through real `filesDir`, atomic writes leave no scratch files | done |
| Offline mutation queue | A true OS-level process kill, driven by `scripts/process-kill-test.sh` as two separate `am instrument` runs with a real `am force-stop` between them | done |
| Compose bindings | Render paths for loading / error / refreshing, in `kwery-compose/src/androidTest` | done |

Every device-only item in the project is now closed. The process-kill test was
also checked against a negative control — running its second half alone,
against an empty queue, fails with the expected assertion, rather than passing
vacuously.

## Deliberately not built

Each of these was specified, considered, and dropped or deferred with the reason
recorded in its spec. They are decisions, not gaps.

| Not built | Why |
|---|---|
| `QuerySignal` cancellation token | `suspendCancellableCoroutine` bridges callback clients in fifteen lines at the call site — [tested](kwery-test/src/test/kotlin/dev/kwery/DivergenceTest.kt), not assumed |
| Mutation filters by key | The `Mutation` object *is* the handle; hook-based libraries need filters because they return a fresh object each render |
| `QueryPriority` parameter | Speculative API. A defaulted parameter is source-compatible to add later; reserving the slot now costs more than it saves |
| `SharingStarted.Lazily` warning | The cache cannot distinguish a leak from a screen left open all afternoon. `observedSinceMillis` reports instead |
| `gcTime` / `Static` warnings | Same reason — both would fire on correct code |
| Typed `combineQueries` overloads | Ergonomics, not parity: `combine` plus destructuring already works |
| Global default query function | Cannot be made type-safe with typed keys |
| `DataStorePersister` | Dropped in favour of file and Room stores |
| Preconfigured persistence in `kwery-test` | Would invert the module graph |

Three of those are the same decision reached three times, and it is worth
stating as a principle: **Kwery does not warn about things it cannot distinguish
from correct usage.** A warning that fires on correct code trains people to
ignore warnings.

## Post-v1

Devtools, cross-process cache sync and streamed queries are specified and
unbuilt. They are tier 4 and were never in the v1 scope.

## How to check this document

```
./gradlew build apiCheck        # everything, including the docs lint
```

The status table that backs this file lives in the project's roadmap, which is
working material and not published. It is checked against the
Definition-of-done boxes in each spec rather than maintained by hand — an open
box there must say why, struck through with a decision or filed under
**Requires a device**, and an open box with no reason is unfinished work.

What *is* verifiable from this repository is everything the claims rest on: the
tests, the `.api` dumps, and the documentation lint that holds every page to
them.
