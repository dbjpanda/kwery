<!--
Kwery uses three gates: spec, then tests, then docs, in that order.
CONTRIBUTING.md has the detail. The short version is below.
-->

## What this changes



## Which gate does this land

- [ ] **Tests** — the behaviour is proven by a test that fails without this change
- [ ] **Docs** — a page in `docs/` describes behaviour that is already tested
- [ ] Neither: build, tooling, or a typo

## Checks

- [ ] `./gradlew build apiCheck` passes
- [ ] If this changes public API, `./gradlew apiDump` was run and the `.api` diff is in this PR
- [ ] If this fixes a bug, I broke the fix again and watched the test fail

<!--
That last box is not ceremony. A test that passes whether or not the code is
correct is worse than no test, and this project has found several of them by
deliberately reverting a fix to see what fails.
-->
