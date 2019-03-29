# Changelog

## Unreleased

### Bug fixes

- [Fix [#29](https://github.com/benedekfazekas/mranderson/issues/29)] Only consider sym a libspec-prefix if it is at the beginning of a list
- [Fix [#28](https://github.com/benedekfazekas/mranderson/issues/28)] Fully qualified sym can be a dotted prefix to refer a `def` in cljs
- [Fix [#14](https://github.com/benedekfazekas/mranderson/issues/14)] Teach mranderson about imports where the namespace name prefixes the record name with a dot, for exampe `(:import foo.bar.baz.FooBar)`

### Changes

- [Fix [#31](https://github.com/benedekfazekas/mranderson/issues/31)] Do not copy anything under `META-INF` directory when unzipping dependency for inlining as meta files are unnecessary in the end product.
- [Fix [#30](https://github.com/benedekfazekas/mranderson/issues/30)] Both `:inline-dep` and `:source-dep` meta tag on dependencies are supported to signal that MrAnderson should inline given dependency.
- Related to [#14](https://github.com/benedekfazekas/mranderson/issues/14) when the ns macro is processed the `:import` section of the ns macro is processed first only when that part is done the rest of the ns macro is considered for replacements.

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
