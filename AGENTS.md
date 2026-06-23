# AGENTS.md

Guidance for AI coding agents working in this repository. Humans: see
[CONTRIBUTING.md](CONTRIBUTING.md) and [doc/design.md](doc/design.md), which this
file deliberately summarizes rather than duplicates.

## What this is

MrAnderson is a Clojure dependency inlining ("shading") tool: it copies a
project's dependencies under a private prefix and rewrites every namespace and
reference so the copies can't collide with anyone else's. Output lands in
`target/srcdeps`. Read [doc/design.md](doc/design.md) before touching the engine.

## Repo map

| Path | What lives there |
|------|------------------|
| `src/mranderson/core.clj` | the pipeline / orchestration, and the `inline-deps` entry point |
| `src/mranderson/move.clj` | the namespace + reference rewriting engine (the subtle part) |
| `src/mranderson/util.clj` | file helpers, jarjar invocation |
| `src/mranderson/dependency/` | dependency resolution (`resolver`) and tree walking (`tree`) |
| `src/leiningen/inline_deps.clj`, `src/mranderson/plugin.clj` | the Leiningen task and middleware |
| `java-src/mranderson/util/` | vendored Jar Jar Links glue (don't reformat) |
| `test/` | the suite; `mranderson.benchmark` is the perf harness |

## Commands

```
lein test                                  # full suite (hits the network; slower first run)
lein test :only mranderson.move-test/move-ns-test   # a single test
lein test :benchmark                       # the perf benchmark (excluded from the normal run)
lein with-profile +eastwood eastwood       # lint
make install                               # the real end-to-end test: inline MrAnderson into itself
```

## Gotchas (read these)

- **Eastwood lints the test namespaces and CI fails on any warning, including
  reflection.** Run eastwood locally after editing tests, or you'll turn the
  build red with something like an untyped `.indexOf`.
- **`test-resources/c` gets written to by a copy test.** If `git status` is dirty
  there after a run, reset with `git checkout -- test-resources/c`. Don't commit
  it.
- **The build is self-hosting.** MrAnderson inlines its own deps using itself, so
  it depends on itself as a plugin. The `Makefile` manages the bootstrap; use it
  rather than fighting `lein` directly for the inlined build.
- **The benchmark is your regression guard for the engine.** If you change
  `mranderson.move`, run `lein test :benchmark` before and after.
- **Unit tests can pass while the pipeline is broken.** The Java/namespace
  overlap bugs (#33, #97, and the body-ref variant) all had green unit tests
  while real inlining failed. For engine changes, add an end-to-end test that
  inlines a real or synthetic dependency.

## Conventions

- Every behaviour change gets a test.
- Add a terse `CHANGELOG.md` entry under `Unreleased`, prefixed with the issue/PR
  link, matching the existing style.
- Focused commits; explain the *why*; reference issues with `[#NN]` (or
  `[Fix #NN]` only when the commit fully closes it).
- Linear history: rebase/fast-forward; rebase-merge for PRs.
- GitHub markdown does not render `--`; use a regular dash or rephrase.
- Don't post to GitHub (issues/PRs/comments) without being asked.
