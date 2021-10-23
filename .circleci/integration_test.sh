#!/usr/bin/env bash
set -Eeuxo pipefail
cd "${BASH_SOURCE%/*}"

# An integration test that exercises that cider-nrepl can successfully use mranderson @ latest.

cd ..
make install
git submodule update --init --recursive

cd .circleci/cider-nrepl
lein clean
# Undo the patch if it was applied already:
git checkout project.clj
git apply ../update-mranderson.patch
# cider-nrepl observes CI, which triggers :pedantic?, which is irrelevant here:
unset CI
lein with-profile -user,-dev inline-deps
lein with-profile -user,-dev,+1.10,+test,+plugin.mranderson/config test
# Leave `git status` clean for local development:
git checkout project.clj
