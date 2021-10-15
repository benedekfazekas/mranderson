#!/usr/bin/env bash
set -Eeuxo pipefail
cd "${BASH_SOURCE%/*}"

# An integration test that exercises that cider-nrepl can successfully use mranderson @ latest.
# We merely verify that the exit code is 0 (via the bash flags above), for the time being:
# we're more interested in seeing how long does the task take.

cd ..
lein clean
lein with-profile -user,-dev,+plugin.mranderson/config install

cd .circleci/cider-nrepl
lein with-profile -user,-dev update-in :plugins conj '[thomasa/mranderson "0.5.4-SNAPSHOT"]' -- inline-deps
