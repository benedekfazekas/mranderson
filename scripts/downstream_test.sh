#!/usr/bin/env bash
set -Eeuxo pipefail
cd "$(git rev-parse --show-toplevel)"

# Build cider-nrepl and/or refactor-nrepl against the locally installed
# mranderson and run their mrandersonized test suites.
#
# We fetch pinned official release sources (instead of git submodules), so it's
# clear exactly which release we test against, the sources live under target/
# (git-ignored, and not indexed by editors), and there's nothing to keep in sync
# in the repo. Each fetched project pins its own mranderson version, so we
# rewrite it to the version we just built and installed - otherwise this would
# silently test some old released mranderson instead of our changes.
#
# Pass one or more targets (cider-nrepl, refactor-nrepl) to run just those; with
# no arguments it runs all of them. CI runs one target per (parallel) matrix job.
#
# Assumes `make install` has already installed mranderson to the local maven repo
# (CI runs that as a separate, hard-failing step; `make integration-test` does it
# via integration_test.sh).

CIDER_NREPL_VERSION="${CIDER_NREPL_VERSION:-0.61.0}"
REFACTOR_NREPL_VERSION="${REFACTOR_NREPL_VERSION:-3.13.0}"

# the mranderson version we build here, e.g. 0.7.1-SNAPSHOT
MRANDERSON_VERSION="$(grep -oE '"[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?"' project.clj | head -1 | tr -d '"')"

DOWNSTREAM_DIR="target/downstream"

# Fetch a pinned release into target/ and repoint its mranderson plugin at the
# version we just installed.
fetch() {
  local name="$1" repo="$2" version="$3"
  local dir="$DOWNSTREAM_DIR/$name"
  mkdir -p "$DOWNSTREAM_DIR"
  rm -rf "$dir"
  git clone --depth 1 --branch "v$version" "$repo" "$dir"
  sed -i.bak -E "s|(thomasa/mranderson) \"[^\"]*\"|\1 \"$MRANDERSON_VERSION\"|" "$dir/project.clj"
  rm -f "$dir/project.clj.bak"
}

test_cider_nrepl() {
  fetch cider-nrepl https://github.com/clojure-emacs/cider-nrepl.git "$CIDER_NREPL_VERSION"
  cd "$DOWNSTREAM_DIR/cider-nrepl"
  # cider-nrepl observes CI, which triggers :pedantic?, which is irrelevant here:
  unset CI
  lein clean
  lein with-profile -user,-dev inline-deps
  lein with-profile -user,-dev,+1.10,+test,+plugin.mranderson/config test
}

test_refactor_nrepl() {
  fetch refactor-nrepl https://github.com/clojure-emacs/refactor-nrepl.git "$REFACTOR_NREPL_VERSION"
  cd "$DOWNSTREAM_DIR/refactor-nrepl"
  lein clean
  make test
}

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  targets=(cider-nrepl refactor-nrepl)
fi

for target in "${targets[@]}"; do
  # a subshell per target so each one's `cd` and `unset CI` stay isolated
  case "$target" in
    cider-nrepl)    (test_cider_nrepl) ;;
    refactor-nrepl) (test_refactor_nrepl) ;;
    *) echo "unknown downstream target: $target (expected cider-nrepl or refactor-nrepl)" >&2; exit 2 ;;
  esac
done
