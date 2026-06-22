#!/usr/bin/env bash
set -Eeuxo pipefail
cd "$(git rev-parse --show-toplevel)"

# Build cider-nrepl and refactor-nrepl against the locally installed mranderson
# and run their mrandersonized test suites. Assumes `make install` has already
# installed mranderson to the local maven repo (CI runs that as a separate,
# hard-failing step; `make integration-test` does it via integration_test.sh).

git submodule update --init --recursive

# cider-nrepl observes CI, which triggers :pedantic?, which is irrelevant here:
unset CI

cd test-resources/cider-nrepl
lein clean
lein with-profile -user,-dev inline-deps
lein with-profile -user,-dev,+1.10,+test,+plugin.mranderson/config test

cd ../refactor-nrepl
lein clean
make test
