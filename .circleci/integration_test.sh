#!/usr/bin/env bash
set -Eeuxo pipefail
cd "${BASH_SOURCE%/*}"

# An integration test that exercises that cider-nrepl and refactor-nrepl can successfully use mranderson @ latest.
# For that, we both build those projects with mranderson in them, and run their mrandersonized test suites.

cd ..
make install
git submodule update --init --recursive

# cider-nrepl observes CI, which triggers :pedantic?, which is irrelevant here:
unset CI

cd .circleci/cider-nrepl
lein clean
# Undo the patch if it was applied already:
git checkout project.clj
git apply ../update-mranderson.patch
lein with-profile -user,-dev inline-deps
lein with-profile -user,-dev,+1.10,+test,+plugin.mranderson/config test
# Leave `git status` clean for local development:
git checkout project.clj

cd ../refactor-nrepl
lein clean
# Undo the patch if it was applied already:
git checkout project.clj
git apply ../update-mranderson-in-refactor-nrepl.patch
make test
# Leave `git status` clean for local development:
git checkout project.clj
