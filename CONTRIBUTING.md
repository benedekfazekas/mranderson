# Contributing to MrAnderson

Thanks for wanting to help. MrAnderson is small but the engine is subtle, so this
guide covers the things that aren't obvious from the code: the self-hosting
build, how to run the tests, and the conventions we follow.

If you want to understand *how the thing works* before changing it, read the
[design doc](doc/design.md). It will save you time.

## Prerequisites

- [Leiningen](https://leiningen.org) 2.9.1 or newer.
- A JDK. CI runs the matrix on JDK 8, 11, 17, and 21, so keep the code compiling
  on 8 (don't reach for language features newer than that).
- Linux or macOS. Windows isn't tested or supported.

## Project layout

```
src/mranderson/core.clj            the pipeline / orchestration
src/mranderson/move.clj            the namespace + reference rewriting engine
src/mranderson/util.clj            file helpers, jarjar invocation
src/mranderson/dependency/         dependency resolution and tree walking
src/leiningen/inline_deps.clj      the `lein inline-deps` task
src/mranderson/plugin.clj          Leiningen middleware
java-src/mranderson/util/          vendored Jar Jar Links glue
test/                              the test suite
doc/                               design and rationale docs
```

## The self-hosting build

MrAnderson inlines its own dependencies using itself, so it depends on itself as
a Leiningen plugin. That's a chicken-and-egg situation, and the `Makefile` exists
to manage it. The important targets:

- `make test` runs the unit tests.
- `make bootstrap-install` installs MrAnderson to your local Maven repo *without*
  inlining. This is what satisfies the self-dependency on the plugin, so the real
  inlined build can run.
- `make inline` bootstraps and then produces an inlined build under
  `target/srcdeps`.
- `make install` installs an *inlined* MrAnderson to your local Maven repo, so
  other projects on your machine can depend on it.
- `make integration-test` runs the downstream tests against cider-nrepl and
  refactor-nrepl (see `scripts/integration_test.sh`).
- `make deploy` deploys an inlined release to Clojars.

For day-to-day work on the engine you mostly want `make test` (or `lein test`).
You only need the bootstrap dance when you're testing the full inlined build.

## Running the tests

```
lein test            # the whole suite
lein test :only mranderson.move-test/move-ns-test    # one test
```

A few things to know:

- **The suite resolves real dependencies.** Several tests inline actual libraries
  (cljfmt, riddley, puget, claypoole) to exercise the pipeline end to end, so the
  first run hits the network and is slower. This is deliberate: these are the
  tests that catch the bugs that unit tests miss.
- **The benchmark is separate.** `mranderson.benchmark` is excluded from normal
  runs. Run it explicitly with `lein test :benchmark`. If you touch the rewriting
  engine, run it before and after and make sure you didn't regress.
- **`test-resources/c` gets dirtied.** One of the copy tests writes output under
  `test-resources/c`. If `git status` shows changes there after a run, that's
  expected; `git checkout -- test-resources/c` to reset.

## Linting

```
lein with-profile +eastwood eastwood
```

The thing that trips people up: **Eastwood lints the test namespaces too**, and
CI fails the build on *any* warning, including reflection warnings. (Clojure
warns when it can't tell a value's Java type and has to fall back to reflection;
CI treats that warning as an error.) So a stray `.indexOf` on an untyped value in
a test will turn the build red. Run eastwood locally before pushing, especially
after editing tests. Shell scripts are also linted, with `shellcheck`.

## Continuous integration

`.github/workflows/ci.yml` has four jobs:

- **test** runs the matrix: JDK 8/11/17/21 by Clojure 1.10/1.11/1.12.
- **lint** runs eastwood and shellcheck.
- **coverage** runs the suite under kaocha + cloverage and uploads to codecov.
- **integration** runs `make install` (a hard failure if the self-inline breaks)
  and then the downstream cider-nrepl/refactor-nrepl tests (a soft failure, since
  those are occasionally flaky for reasons outside this repo).

The `make install` step is the real end-to-end test: it inlines MrAnderson into
itself. If your change breaks the engine, that's usually where it shows up.

## Conventions

- **Tests.** Every behaviour change needs a test. For engine bugs, prefer an
  end-to-end test that inlines a real (or, if needed, synthetic) dependency over a
  unit test that feeds clean inputs; the latter has a habit of passing while the
  real pipeline is broken.
- **Changelog.** Add a terse entry to the `Unreleased` section of
  `CHANGELOG.md`, prefixed with the issue/PR link, in the style of the existing
  entries.
- **Commits.** Focused, logical commits with a message that explains the *why*.
  Reference issues with `[#NN]`, or `[Fix #NN]` only when the commit fully closes
  the issue.
- **History.** We keep history linear: rebase/fast-forward, and rebase-merge for
  PRs (no merge commits).
- **Markdown.** GitHub doesn't render `--`. Use a regular dash or rephrase.

## Releasing

Releases go to Clojars as `thomasa/mranderson`. Bump the version, make sure the
`CHANGELOG` is in order, then `make deploy` (which inlines MrAnderson before
deploying). Deploys are unsigned and use a Clojars token from the environment.
