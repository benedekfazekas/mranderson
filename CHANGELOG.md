# Changelog

## Unreleased

### Bug fixes

- [#82](https://github.com/benedekfazekas/mranderson/issues/82) Leave reader-discarded (`#_`) forms in the `ns` macro untouched during a rename. A symbol buried inside a discarded `:require`/`:import` (e.g. `#_[a.b :as c]`) was previously rewritten, since the guards only skipped the discard node itself, not its contents

### Changes

- [#65](https://github.com/benedekfazekas/mranderson/issues/65) Mark internal namespaces (`mranderson.util`, `mranderson.move`, `mranderson.dependency.*`) `:no-doc` so cljdoc documents only the public API (`mranderson.core`, `mranderson.plugin`, `leiningen.inline-deps`)
- [#82](https://github.com/benedekfazekas/mranderson/issues/82) Centralize reader-discard handling in a new internal `mranderson.zloc` namespace that wraps rewrite-clj navigation, instead of scattering `:uneval` checks through `mranderson.move`

## 0.6.1

### Bug fixes

- Only add a namespace to `:aot` (Leiningen plugin) when its `ns` form actually has a `:gen-class` clause, not when `:gen-class` merely appears as text in a docstring or comment
- Leave a fully-qualified reference to a repackaged Java class (e.g. `(some.pkg.Widget. ...)`) untouched during the namespace move when its package is also a moved namespace, so the java-import pass can point it at the jarjar-repackaged class instead of the namespace prefix (otherwise it threw `ClassNotFoundException` at runtime). Deftype/defrecord classes, which have no `.class` file, still move with their namespace
- [#33](https://github.com/benedekfazekas/mranderson/issues/33) Split a mixed `(:import ...)` correctly when a deftype class and a repackaged Java class share a package (e.g. claypoole): the Java class now points at its jarjar package even though the namespace move has already prefixed the shared package
- [#97](https://github.com/benedekfazekas/mranderson/issues/97) Prefix repackaged Java classes referenced in a namespace body even when the file has no `(:import ...)` form (previously such references were left bare and threw `ClassNotFoundException` at runtime)

### Changes

- Speed up inlining (roughly 2x on a medium dependency tree) by rewriting namespace references in a single pass: in resolved-tree mode every source file is now parsed once instead of once per dependency whose scope overlapped it (it was effectively O(files x namespaces)), and each file's tokens are dispatched to the matching rename by first segment rather than checked against every rename

## 0.6.0

### Bug fixes

- [fix [#61](https://github.com/benedekfazekas/mranderson/issues/61)] Repoint `(load "…")` resource paths when a namespace is relocated, so libraries that `load` companion files (e.g. `tools.deps.alpha`) inline correctly
- [fix [#73](https://github.com/benedekfazekas/mranderson/issues/73)] Munge the package of fully-qualified class/record references to Java style so a dash in the prefix doesn't produce broken references
- [fix [#64](https://github.com/benedekfazekas/mranderson/issues/64)] Strip non-alphanumeric characters (e.g. a `/` in a `n/a` version) from the generated prefix so they don't break imports
- [fix [#54](https://github.com/benedekfazekas/mranderson/issues/54)] [fix [#92](https://github.com/benedekfazekas/mranderson/issues/92)] Rewrite Java `:import`s of inlined classes in top-level dependency namespaces and in the consuming project's own sources, which the per-dependency pass missed
- [fix [#52](https://github.com/benedekfazekas/mranderson/issues/52)] Rewrite Java `:import`s by exact class rather than by package, so a class that merely shares a package with a repackaged one is left untouched
- [[#33](https://github.com/benedekfazekas/mranderson/issues/33)] Split a mixed `(:import [pkg A B])` so a deftype-generated class and a real Java class in the same package each get the correct prefix
- Java-class repackaging now operates on the configured `srcdeps` directory instead of a hardcoded `target/srcdeps`, so it works with `mranderson.core/inline-deps` and a custom `:target-path`
- [fix [#88](https://github.com/benedekfazekas/mranderson/issues/88)] When removing a dependency's compiled classes, only delete `.class` files instead of the whole top-level directory, so project sources sharing a root namespace with an inlined lib aren't clobbered
- [fix [#78](https://github.com/benedekfazekas/mranderson/issues/78)] Fix watermarking moved namespaces 
- [fix [#53](https://github.com/benedekfazekas/mranderson/issues/53)] Fix inlining where one directory name in the library is a substring of an other directory name
- [fix [#49](https://github.com/benedekfazekas/mranderson/issues/49)] Options accepted from both CLI and the project map
- [fix [#72](https://github.com/benedekfazekas/mranderson/issues/72)] Mranderson rewrites some clj/cljc files twice (instaparse)
- [fix [#56](https://github.com/benedekfazekas/mranderson/issues/56)] Fix intermittent broken files when inlining
- [fix [#90](https://github.com/benedekfazekas/mranderson/issues/90)] Fix spade/core.cljs not found
- [fix [#76](https://github.com/benedekfazekas/mranderson/issues/76)] Consider `org.clojure/core.rrb-vector` as part of clojure.core

### Changes

- [#89](https://github.com/benedekfazekas/mranderson/issues/89) Warn when `:aot` is configured, since inlining AOT-compiled namespaces produces broken prefixes
- [#69](https://github.com/benedekfazekas/mranderson/issues/69) Document the local build/install workflow and fix the `Makefile` so `make install` installs an inlined MrAnderson to the local maven repo
- [feature [#42](https://github.com/benedekfazekas/mranderson/issues/42)] add `mranderson.core/inline-deps`, a Leiningen-free entry point usable from `tools.build`/`tools.deps`
- [feature [#42](https://github.com/benedekfazekas/mranderson/issues/42)] uncouple MrAnderson from leiningen to support general use 
- [maint [#66](https://github.com/benedekfazekas/mranderson/issues/66)] bump MrAnderson dependencies
- [fix [#63](https://github.com/benedekfazekas/mranderson/pull/63)] introduce `mranderson.internal.no-parallelism` as on option temporarily
- integration tests against `cider-nrepl` and `refactor-nrepl`
- improve CI matrix
- Simplify internal threading setup
- [fix [#58](https://github.com/benedekfazekas/mranderson/issues/58)] Offer a new `:included-source-paths` option, which is described in the README.
- use `org.pantsbuild.jarjar:1.7.2` instead of `jarjar:1.3`. Also abandoned but still an upgrade
- exclude `clj-kondo.exports` from jar extraction by @bbatsov
- migrate from cricleci to github actions by @bbatsov
- dependency upgrades by @bbatsov
- fix minor code issues by @bbatsov
- Update cider-nrepl submodule and mark integration tests as soft failures by @bbatsov

## 0.5.3

### Bug fixes

- [fix [#47](https://github.com/benedekfazekas/mranderson/issues/47)] Error when inlining cljfmt 0.7

## 0.5.2

### Bug fixes

- [Fix [#44](https://github.com/benedekfazekas/mranderson/issues/44)] Ignore invalid duplicates where file location does not match namespace declaration -- thanks @xsc
- Fix NPE when trying to process cljc file without a namespace declaration

### Changes

- minimum leiningen version upgraded to 2.9.1
- some internal improvements: tests added, kaocha added as test runner, codecov added to CI

## 0.5.1

### Bug fixes

- [Fix [#29](https://github.com/benedekfazekas/mranderson/issues/29)] Only consider sym a libspec-prefix if it is at the beginning of a list
- [Fix [#28](https://github.com/benedekfazekas/mranderson/issues/28)] Fully qualified sym can be a dotted prefix to refer a `def` in cljs
- [Fix [#14](https://github.com/benedekfazekas/mranderson/issues/14)] Teach mranderson about imports where the namespace name prefixes the record name with a dot, for exampe `(:import foo.bar.baz.FooBar)`

### Changes

- [Fix [#31](https://github.com/benedekfazekas/mranderson/issues/31)] Do not copy anything under `META-INF` directory when unzipping dependency for inlining as meta files are unnecessary in the end product.
- [Fix [#30](https://github.com/benedekfazekas/mranderson/issues/30)] Both `:inline-dep` and `:source-dep` meta tag on dependencies are supported to signal that MrAnderson should inline given dependency.
- Related to [#14](https://github.com/benedekfazekas/mranderson/issues/14) when the ns macro is processed the `:import` section of the ns macro is processed first. Only when that part is done the rest of the ns macro is considered for replacements.

## 0.5.0

### Breaking changes

- `source-deps` leiningen task is renamed to `inline-deps`

### Changes

- introducing **unresolved tree** mode where mranderson works on an unresolved dependency tree, walks it depth first when processing it. applies transient deps hygiene
- introduce **resolved tree** mode where resolved dependency tree is flattened into a topological ordered list for processing. every depedency (transient ones too) is considered first level
- namespaces are split when processed: the ns macro is parsed and modified with `rewrite-clj` the body of the ns is processed textually
- process files in parallel when updating changed namespace in all dependent files ([parallel](https://github.com/reborg/parallel) is used)
- add some tests and auto build via circleci
- breaking up `mranderson.source-deps` ns into multiple namespaces for readability/maintainablity
- mranderson understands `:mranderson` section in the project.clj for certain config options (`project-prefix`, `overrides`, `expositions`, `shadowing-only`)
- add `overrides` config option for the **unresolved tree** mode to make overriding transient deps possible
- add `expositions` config option for the **unresolved tree** mode to override transient deps hygiene so a transient dependency would be available for the project's own source files
- track changes in a this file from this release instead of the github releases page

### Bug fixes
- [Fix [#7](https://github.com/benedekfazekas/mranderson/issues/7)] Both **unresolved tree** and **resolved tree** modes fix overriden deps break topological sort issue. The topological order is not important when the unresolved tree is used whilest the topological order is derrived from the unresolved tree when resolved tree is used in **resolved tree** mode
- [Fix [#19](https://github.com/benedekfazekas/mranderson/issues/19)] Add watermark to mrandersoned dependency nses
- [Fix [#21](https://github.com/benedekfazekas/mranderson/issues/21)] Rewrite README to make it more informative (hopefully)
- [Fix [#25](https://github.com/benedekfazekas/mranderson/issues/25)] project-prefix's default is unique so mrandersoned libs using the same mranderson version can't clash

## Previous versions

Please see the [releases](https://github.com/benedekfazekas/mranderson/releases) page on github.
