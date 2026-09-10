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

CIDER_NREPL_VERSION="${CIDER_NREPL_VERSION:-0.62.2}"
REFACTOR_NREPL_VERSION="${REFACTOR_NREPL_VERSION:-3.13.0}"
# cider-nrepl builds with tools.build, so its build alias puts tools.deps (and
# deps-deploy's Maven bits) on the same classpath as mranderson. Run it against
# the current versions of that tooling rather than whatever the release pins, so
# a clash between mranderson's artifact and a newer resolver (#134) shows up
# here instead of downstream.
TOOLS_BUILD_VERSION="${TOOLS_BUILD_VERSION:-0.10.14}"
DEPS_DEPLOY_VERSION="${DEPS_DEPLOY_VERSION:-0.2.5}"

# the mranderson version we build here, e.g. 0.7.1-SNAPSHOT
MRANDERSON_VERSION="$(grep -oE '"[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?"' project.clj | head -1 | tr -d '"')"

DOWNSTREAM_DIR="target/downstream"

# Fetch a pinned release into target/.
fetch() {
  local name="$1" repo="$2" version="$3"
  local dir="$DOWNSTREAM_DIR/$name"
  mkdir -p "$DOWNSTREAM_DIR"
  rm -rf "$dir"
  git clone --depth 1 --branch "v$version" "$repo" "$dir"
}

test_cider_nrepl() {
  fetch cider-nrepl https://github.com/clojure-emacs/cider-nrepl.git "$CIDER_NREPL_VERSION"
  cd "$DOWNSTREAM_DIR/cider-nrepl"
  # Repoint the :build alias at the mranderson we just installed and at the
  # current build tooling. A user-level deps.edn is the least invasive way in:
  # its :build alias merges with the project's, so :override-deps applies on top
  # of the release's own pins without editing the checkout.
  local config_dir="$PWD/target/clj-config"
  mkdir -p "$config_dir"
  cat > "$config_dir/deps.edn" <<EOF
{:aliases {:build {:override-deps {thomasa/mranderson {:mvn/version "$MRANDERSON_VERSION"}
                                   io.github.clojure/tools.build {:mvn/version "$TOOLS_BUILD_VERSION"}
                                   slipset/deps-deploy {:mvn/version "$DEPS_DEPLOY_VERSION"}}}}}
EOF
  export CLJ_CONFIG="$config_dir"
  clojure -Stree -T:build | grep -E "^[a-z]"
  # inline the shaded deps with the mranderson under test, then run the suite
  # against the inlined sources
  make inlined-test
}

test_refactor_nrepl() {
  fetch refactor-nrepl https://github.com/clojure-emacs/refactor-nrepl.git "$REFACTOR_NREPL_VERSION"
  cd "$DOWNSTREAM_DIR/refactor-nrepl"
  # repoint the lein plugin at the mranderson we just installed
  sed -i.bak -E "s|(thomasa/mranderson) \"[^\"]*\"|\1 \"$MRANDERSON_VERSION\"|" project.clj
  rm -f project.clj.bak
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
