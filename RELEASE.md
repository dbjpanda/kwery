# Release readiness

Where Kwery actually stands, checked against the repository rather than
remembered. Every claim here is traceable to a test, a roadmap file, or a
`./gradlew build apiCheck` run.

**Status: publishable as `0.1.0-SNAPSHOT`, with one scope decision outstanding.**
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
publish to `dev.kwery` at `0.1.0-SNAPSHOT`, each with a sources jar, a javadoc
jar and a POM carrying the Apache-2.0 licence. Verified by publishing to Maven
Local and reading the artifacts back.

Sources are not decoration here: without them a consumer stepping into Kwery in
a debugger sees bytecode, and the KDoc explaining `staleTime` versus `gcTime` —
the two things every user misreads — never reaches them.

Still outstanding for a *public* release: signing keys and a Central account,
both of which need credentials rather than code.

**2. `kwery-persist` ships only file-backed stores.** `FilePersister` and
`FileMutationQueueStore` are tested and correct, but both rewrite the whole file
on every change. That is fine for a few hundred entries and wrong for tens of
thousands. The roadmap specifies Room-backed implementations for that case
([14](docs/roadmap/14-offline-mutation-queue.md),
[15](docs/roadmap/15-persistence.md)) and neither exists.

Whether this blocks v1 is a scope decision, not a technical one: shipping
file-only with the limit documented is defensible. Shipping it *undocumented*
is not, and there is currently no benchmark saying where the limit falls.

## Needs a device

Three tests cannot run in this environment and are tracked rather than pretended
away. None blocks a release; all three would strengthen one.

| Feature | Test |
|---|---|
| [01](docs/roadmap/01-query-keys.md) | R8-shrunk build proving canonical key strings survive minification |
| [07](docs/roadmap/07-refetch-triggers.md) | A captive-portal network (connected, not validated) reports offline |
| [17](docs/roadmap/17-compose-bindings.md) | Compose render paths for loading / error / refreshing |

The first is the one to run first. Key encoding feeds persistence, so if R8
rewrites something the cache silently misses on every cold start after release —
a failure that cannot happen in debug builds.

## Deliberately not built

Each of these was specified, considered, and dropped or deferred with the reason
recorded in its roadmap file. They are decisions, not gaps.

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

Features [22](docs/roadmap/22-devtools.md) (devtools),
[23](docs/roadmap/23-cross-process-sync.md) (cross-process cache sync) and
[24](docs/roadmap/24-streamed-queries.md) (streamed queries) are specified and
unbuilt. They are tier 4 and were never in the v1 scope.

## How to check this document

```
./gradlew build apiCheck        # everything, including the docs lint
```

The status table in [`docs/roadmap/README.md`](docs/roadmap/README.md) is the
source of truth, and it is checked against the Definition-of-done boxes in each
feature file rather than maintained by hand. An open box there must say why:
struck through with a decision, or under a **Requires a device** heading. An
open box with no reason is unfinished work.
