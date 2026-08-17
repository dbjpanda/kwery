#!/usr/bin/env bash
#
# Fetch TanStack Query's documentation and query-core test suite into
# .reference/, at the revision Kwery tracks parity against.
#
# .reference/ is NOT committed. This script exists so every contributor gets
# byte-identical reference material, and so the pinned revision lives in version
# control even though its contents do not.
#
# Run once after cloning, before doing gate-2 (test) work:
#
#     ./scripts/vendor-reference.sh
#
set -euo pipefail

# The revision every parity claim in docs/roadmap/ is checked against.
# Bumping this is a deliberate act: re-check every parity table against the
# upstream diff, and land it as its own commit.
readonly PINNED_REV="dce04b5c5ab1e71cd5de1ea1481a48c5a2d9c9a5"
readonly UPSTREAM="https://github.com/TanStack/query.git"

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly TARGET="${REPO_ROOT}/.reference/tanstack-query"

if [[ -d "${TARGET}" ]]; then
    if [[ "${1:-}" != "--force" ]]; then
        echo "${TARGET} already exists. Re-run with --force to replace it." >&2
        exit 1
    fi
    rm -rf "${TARGET}"
fi

echo "Vendoring TanStack Query @ ${PINNED_REV:0:7} ..."
mkdir -p "$(dirname "${TARGET}")"

# Blobless + sparse: the full history and source tree are not needed, only the
# docs and the query-core tests.
git clone --filter=blob:none --sparse --no-checkout "${UPSTREAM}" "${TARGET}" --quiet
git -C "${TARGET}" sparse-checkout set docs packages/query-core/src/__tests__
git -C "${TARGET}" checkout --quiet "${PINNED_REV}"

# Drop the nested repository so it cannot be confused for a submodule and so
# the pin cannot drift by someone pulling inside it.
rm -rf "${TARGET}/.git"

doc_count=$(find "${TARGET}/docs" -name '*.md' | wc -l | tr -d ' ')
test_count=$(find "${TARGET}/packages/query-core/src/__tests__" -name '*.tsx' -o -name '*.ts' | wc -l | tr -d ' ')

echo "Done."
echo "  docs:  ${doc_count} markdown files"
echo "  tests: ${test_count} files (the behavioural spec for gate 2)"
echo
echo "Upstream is MIT licensed, Copyright (c) 2021-present Tanner Linsley."
echo "See ${TARGET}/LICENSE and this repository's NOTICE."
