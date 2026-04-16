#!/usr/bin/env bash
set -Eeuxo pipefail
cd "$(git rev-parse --show-toplevel)"

# An integration test that exercises that cider-nrepl and refactor-nrepl can successfully use mranderson @ latest.
# For that, we both build those projects with mranderson in them, and run their mrandersonized test suites.

make install
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
