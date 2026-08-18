# Contributing to Kwery

## First: fetch the reference material

Kwery targets behavioural parity with [TanStack Query](https://tanstack.com/query/latest),
and that parity is checked against TanStack's own documentation and test suite
rather than against anyone's memory of how it behaves.

That material is **not committed** to this repository. Fetch it once after
cloning:

```sh
./scripts/vendor-reference.sh
```

This puts TanStack Query's `docs/` and its `query-core` test suite under
`.reference/`, at the exact revision recorded in the script. `.reference/` is
gitignored, so it never appears in a diff and never bloats the repository.

You need it before doing any test work, and the links from the specs
into `.reference/` only resolve once you have run it.

| | |
|---|---|
| `.reference/tanstack-query/docs/` | 494 markdown files. Framework-agnostic behaviour is under `docs/framework/react/guides/`. |
| `.reference/tanstack-query/packages/query-core/src/__tests__/` | ~16,000 lines of behavioural tests. `query-core` is the framework-agnostic layer `kwery-core` mirrors, so these are close to an executable specification. |

Bumping the pinned revision is a deliberate act, not routine maintenance:
re-check every parity table in the specs against the upstream diff, and
land the bump as its own commit so the behavioural delta is reviewable on its
own.

## Building

```sh
./gradlew build      # compile + test
./gradlew apiCheck   # public API has not changed unintentionally
./gradlew apiDump    # accept an intentional public API change
```

Requires JDK 17. Published artifacts target JVM 11.

**Do not upgrade Gradle past 9.5.x.** The reason is non-obvious and is recorded
in [CLAUDE.md](CLAUDE.md) — briefly, Gradle 9.6+ forces AGP 9.x, whose built-in
Kotlin silently disables KSP and stops `apiCheck` covering Android modules at
all.

## The three gates

A feature is complete only when all three have been passed, **in order**:

1. **Spec** — a design document with the open questions resolved, or explicitly
   deferred with a reason. Specs live in `docs/roadmap/`, which is **not part of
   this repository**: it is working material, and publishing it would put four
   tiers of open questions in front of people who want to use the library. Ask
   for it if you are taking on a feature.
2. **Tests** — every box in that spec's "Definition of done" ticked, suite green.
3. **Docs** — `docs/<feature>.md`, user-facing, with examples that compile.

Gate 3 never precedes gate 2. Documentation describes behaviour that has been
proven, not behaviour that was intended — the "what goes wrong" section of a
docs page can only be written after the tests exist, and it is the section
readers actually need.

[RELEASE.md](RELEASE.md) records what each module is ready for, what was
deliberately not built and why, and what needs a device. The headline count of
completed features lives in [README.md](README.md), so it is stated once.

## Releasing

A tag is immutable, so everything that names the version has to be right before
it is pushed, not after.

1. `./gradlew clean` then `./gradlew build apiCheck` — and read the output. The
   build cache can return a full green in two seconds, which looks identical to
   a stale result; `--rerun-tasks` if you want to be certain the suite actually
   ran.
2. Drop `-SNAPSHOT` from `kwery` in `gradle/libs.versions.toml`.
3. Update the version in the README's install block. The JitPack badge tracks
   the latest tag on its own; the code block does not.
4. Update `RELEASE.md` if what is done, deferred or device-blocked has changed.
5. Commit, then `git tag -a vX.Y.Z` with notes saying what is in it **and what
   is not** — a version number is a promise.
6. Push the branch and the tag.
7. Trigger the JitPack build so the first person to depend on it does not wait
   three minutes for a cold build:
   `curl -s https://jitpack.io/com/github/dbjpanda/kwery/kwery-core/vX.Y.Z/kwery-core-vX.Y.Z.pom -o /dev/null`
8. `gh release create vX.Y.Z --notes-file <file>`.

## Publishing to Maven Central

Not done yet — this is what it needs. Steps 1 to 3 are one-off and need a
browser; the rest is `./gradlew`.

1. **Register the namespace.** Create an account at
   [central.sonatype.com](https://central.sonatype.com) and add the namespace
   `io.github.dbjpanda`. It is verified from GitHub account ownership, which is
   why the group is `io.github.<user>` and not a domain — a domain-based group
   such as `dev.kwery` would require proving control of `kwery.dev` by DNS
   record.
2. **Generate a signing key** and publish the public half, or nothing can
   verify the artifacts:
   ```sh
   gpg --full-generate-key                  # RSA 4096, no expiry is fine
   gpg --list-secret-keys --keyid-format=long
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID> # the value for signingInMemoryKey
   ```
3. **Generate a Portal token** (Account → Generate User Token).
4. **Build a signed bundle:**
   ```sh
   ./gradlew centralBundle \
     -PsigningInMemoryKey="$(gpg --armor --export-secret-keys <KEY_ID>)" \
     -PsigningInMemoryKeyPassword=<passphrase>
   ```
   The task warns if the bundle contains no signatures. Central rejects an
   unsigned bundle *after* upload, which is a slow way to discover it.
5. **Upload** `build/central/kwery-<version>-bundle.zip` through the Portal UI,
   or with its API, and release the deployment once validation passes.

Keep the key out of the repository and out of shell history — pass it through an
environment variable or a `~/.gradle/gradle.properties` that is not in a
project directory. Signing is skipped entirely when no key is present, so
`./gradlew build` works for everyone else.

## Writing tests

- **Start from TanStack's tests**, not from the design document. Read the test
  *names* first — they encode edge cases no spec anticipates. Real examples that
  changed Kwery's implementation: *"should use the longest garbage collection
  time it has seen"*, *"the previous query status should be kept when
  refetching"*, *"cancelling a rejected query should not have any effect"*.
- **When a case is deliberately not ported, record it in the feature's parity
  table with a reason.** Silent omission is how a parity claim becomes false.
- **No real `delay()`.** Time runs through the injectable `TimeSource` and
  `kotlinx-coroutines-test`. A suite that takes minutes is a suite that gets
  skipped.
- **Assert request counts, not just final state.** Most meaningful claims about
  a caching library — "deduplicated", "did not refetch", "rotation is free" —
  are request-count claims. `TestQueryClient` records them.
- **Prove the test can fail.** Break the code deliberately and watch it go red
  before trusting it. Several of Kwery's most important guards were confirmed
  this way, and a green test that cannot fail is decoration.

## Code

- `kwery-core` has **no Android dependencies**, ever. Android concerns enter
  through interfaces implemented in `kwery-android`.
- Explicit API mode and `allWarningsAsErrors` are on. Public API is permanent
  once released; `apiCheck` makes every change to it a reviewed diff.
- Public API gets KDoc — especially the two things every user misunderstands:
  `staleTime` vs `gcTime`, and `status` vs `fetchStatus`.

## Commits

One gate per commit where practical. A re-vendor of `.reference/` lands as its
own commit so the upstream delta stays reviewable separately from Kwery changes.
