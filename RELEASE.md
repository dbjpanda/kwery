# Release readiness

Where Kwery actually stands, checked against the repository rather than
remembered. Every claim here is traceable to a test or a
`./gradlew build apiCheck` run.

**Status: released as `v0.1.0` via JitPack. Not yet on Maven Central.**
See [Blockers](#blockers).

## What is done

**19 of 24 features pass all three gates** — spec written, tests green, user
documentation published. 354 tests across four modules, no real `delay()` in any
of them, and a full `build` plus `apiCheck` green.

| Module | Purpose | State |
|---|---|---|
| `kwery-core` | cache, observers, retries, mutations, infinite queries | complete for v1 |
| `kwery-test` | `TestQueryClient`, virtual clock, request recording | complete for v1 |
| `kwery-persist` | persistence contracts, file stores, offline queue | file-backed only |
| `kwery-android` | `FocusManager`, `OnlineManager` | complete for v1 |
| `kwery-compose` | `rememberQuery` and friends | complete for v1 |
| `docs-lint` | holds the documentation to the published API | not published |

Twenty user-facing documentation pages, every identifier and named argument in
them checked against the `.api` dumps on every build.

## Blockers

**~~1. There is no publishing configuration.~~ Done.** All five library modules
publish to `io.github.dbjpanda` at `0.1.0`, each with a sources jar, a javadoc
jar and a POM carrying the Apache-2.0 licence. Verified by publishing to Maven
Local and reading the artifacts back.

Sources are not decoration here: without them a consumer stepping into Kwery in
a debugger sees bytecode, and the KDoc explaining `staleTime` versus `gcTime` —
the two things every user misreads — never reaches them.

Still outstanding for a *public* release: signing keys and a Central account,
both of which need credentials rather than code.

**2. `kwery-persist` ships only file-backed stores — and the measurement says
that is fine.** This was written as a blocker on the assumption that restoring a
large cache from a file would be too slow. It is not:

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
would ever notice it. It is bounded in practice because the persist loop now
skips writing when nothing changed — a bug found the same way, by counting
writes rather than checking results.

So Room is **not** a v1 blocker, and the case for it is row-level updates rather
than startup latency. That is a different design and a lower priority than the
roadmap assumed. Shipping file-only, with the amplification documented, is the
recommendation.

## Needs a device

Three tests cannot run in this environment and are tracked rather than pretended
away. None blocks a release; all three would strengthen one.

| Area | Test |
|---|---|
| Query keys | R8-shrunk build proving canonical key strings survive minification |
| Refetch triggers | A captive-portal network (connected, not validated) reports offline |
| Compose bindings | Render paths for loading / error / refreshing |

The first is the one to run first. Key encoding feeds persistence, so if R8
rewrites something the cache silently misses on every cold start after release —
a failure that cannot happen in debug builds.

## Deliberately not built

Each of these was specified, considered, and dropped or deferred with the reason
recorded in its spec. They are decisions, not gaps.

| Not built | Why |
|---|---|
| `QuerySignal` cancellation token | `suspendCancellableCoroutine` bridges callback clients in fifteen lines at the call site — [tested](kwery-test/src/test/kotlin/dev/kwery/DivergenceTest.kt), not assumed |
| Mutation filters by key | The `Mutation` object *is* the handle; TanStack needs filters because its hooks return a fresh object each render |
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

The status table that backs this document lives in the project's roadmap, which
is working material and not published. It is checked against the
Definition-of-done boxes in each spec rather than maintained by hand — an open
box there must say why, struck through with a decision or filed under
**Requires a device**, and an open box with no reason is unfinished work.

What *is* verifiable from this repository is everything the claims rest on: the
tests, the `.api` dumps, and the documentation lint that holds every page to
them.
