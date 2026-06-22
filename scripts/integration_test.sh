#!/usr/bin/env bash
set -Eeuxo pipefail
cd "$(git rev-parse --show-toplevel)"

# Full integration test: build and install mranderson, then exercise it through
# cider-nrepl and refactor-nrepl. This is the all-in-one entry point for local
# use (`make integration-test`). CI instead runs the two phases as separate
# steps so the deterministic install can fail hard while the downstream builds
# (which track moving upstream targets) stay soft.

make install
scripts/downstream_test.sh
