# Changelog

## Unreleased

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
- [Fix #19] Add watermark to mrandersoned dependency nses
- [Fix #21] Rewrite README to make it more informative (hopefully)
- [Fix #25] project-prefix's default is unique so mrandersoned libs using the same mranderson version can't clash

## Previous versions

Please see the [releases](https://github.com/benedekfazekas/mranderson/releases) page on github.
