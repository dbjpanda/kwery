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

Releases are automated. You do not pick a version number, write a changelog, or
tag anything.

1. Merge work to `main` using Conventional Commit subjects — `feat:`, `fix:`,
   `docs:`, `build:`, `test:`, `chore:`, and `feat!:` or a `BREAKING CHANGE:`
   footer for anything incompatible.
2. **release-please** keeps a Release PR open, accumulating every change since
   the last release. It derives the next version from those commit types
   (`fix` → patch, `feat` → minor, breaking → major while pre-1.0 it bumps the
   minor), rewrites `CHANGELOG.md`, and bumps `kwery` in
   `gradle/libs.versions.toml`.
3. **Merging that PR is the release.** It tags, creates the GitHub Release, and
   the same workflow run then verifies and publishes to Maven Central.

The Release PR is the review gate. Nothing reaches Central without it being
merged deliberately.

### Why the version file holds a released version, not a snapshot

`kwery = "0.1.1"` on `main` is the last **released** version, and that is the
release-please convention rather than an oversight. The bump and the tag happen
in the same merge, so there is no window in which the branch claims a version
that was never cut. Do not edit that line by hand; the
`# x-release-please-version` marker is how the tool finds it.

### Why publishing lives in the release-please workflow

Not in its own workflow triggered by `on: release`. **GitHub does not trigger
workflows from events created with `GITHUB_TOKEN`**, and a release-please
release is authored by `github-actions[bot]` — so an `on: release` workflow
never fires. It does not fail; it simply never runs, which is a much worse way
to find out, and is exactly what happened on the first attempt. Chaining the
publish job off the action's own `release_created` output avoids it without
needing a personal access token.

### What runs before anything is published

Publishing is automatic, so an upload is permanent the moment it succeeds. Two checks run first, in this order and in the same job:

- **the tag must match the version in `libs.versions.toml`**, and must not be a
  snapshot. Under automatic release a mismatch is unfixable — a `v0.2.0` tag
  publishing artifacts labelled `0.1.1` cannot be taken back.
- **the whole suite and `apiCheck`**, because a failing test discovered after
  publication has no remedy.

### If a publish fails

The workflow can be re-run against an existing tag from the Actions tab —
**Release Please → Run workflow**, with the tag as input. A failed upload is not
a reason to burn a version number, and on a registry where nothing can be
deleted, burning one is not free.

Note that `publishToMavenCentral` runs with `--no-configuration-cache`. That is
required, not tidiness: the publish task cannot be serialized into the
configuration cache this build enables globally, and the plugin fails rather
than publishing something wrong. See gradle/gradle#22779.

### Secrets it needs

Set once, on the repository:

| Secret | What |
|---|---|
| `SIGNING_KEY` | armoured GPG private key: `gpg --armor --export-secret-keys <KEY_ID>` |
| `SIGNING_KEY_PASSWORD` | the passphrase, empty if the key has none |
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | a Central Portal user token, from Account → Generate User Token |

## Instrumentation tests

Most behaviour is testable on the JVM, and should be: the suite runs in seconds
because nothing waits on real time or a device. A handful of things cannot be,
and those live in `androidTest`.

```sh
$ANDROID_HOME/emulator/emulator -avd <name> -no-window -no-audio &
./gradlew :kwery-android:connectedDebugAndroidTest   # connectivity, storage
./gradlew :sample:connectedReleaseAndroidTest        # R8, against a minified build
```

The sample runs its instrumentation against **release** on purpose. Kwery
encodes enum key parts by `name`, and only a minified build can show whether R8
rewrites them.

**A zero-test run is a failure, not a pass.** If the instrumentation process
dies before the first test, the report shows zero tests and zero failures, which
looks like success in any summary that counts failures. That happened three
times while writing these. `KeyEncodingR8Test` includes a test asserting the
build really is minified for the same reason: a test that quietly stops testing
is worse than one that fails.

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
