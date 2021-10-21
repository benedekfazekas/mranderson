#!/usr/bin/env bash
set -Eeuxo pipefail
cd "${BASH_SOURCE%/*}"

# An integration test that exercises that cider-nrepl can successfully use mranderson @ latest.
# We merely verify that the exit code is 0 (via the bash flags above), for the time being:
# we're more interested in seeing how long does the task take.

cd ..
lein clean
lein with-profile -user,-dev install
git submodule update --init --recursive

cd .circleci/cider-nrepl
lein clean
# Undo the patch if it was applied already:
git checkout project.clj
git apply ../update-mranderson.patch
lein with-profile -user,-dev inline-deps
# Leave `git status` clean for local development:
git checkout project.clj
