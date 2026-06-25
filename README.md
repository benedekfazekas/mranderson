[![CI](https://github.com/benedekfazekas/mranderson/actions/workflows/ci.yml/badge.svg)](https://github.com/benedekfazekas/mranderson/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/benedekfazekas/mranderson/branch/master/graph/badge.svg?token=St3R9M1IgF)](https://codecov.io/gh/benedekfazekas/mranderson)
[![Clojars Project](https://img.shields.io/clojars/v/thomasa/mranderson.svg)](https://clojars.org/thomasa/mranderson)
[![cljdoc badge](https://cljdoc.org/badge/thomasa/mranderson)](https://cljdoc.org/d/thomasa/mranderson/CURRENT)
[![Join chat](https://img.shields.io/badge/slack-join_chat-brightgreen.svg)](https://clojurians.slack.com/archives/C04HWRD2D32)

# MrAnderson

MrAnderson is a dependency inlining tool for Clojure. It copies the dependencies you choose into your own project under a private name and rewrites every reference to them, so your copies can't collide with anyone else's on the classpath. (This technique has a few names: inlining, shading, vendoring. They mean the same thing here.)

The problem it solves: the JVM loads one version of any given class, full stop. If two of your dependencies need different, incompatible versions of a third one, something breaks and no amount of version-pinning fixes it. Inlining sidesteps that by giving your copy a name nobody else can clash with.

## Is it good? Should I use it?

Yes and yes.

Reach for it when you have a dependency conflict you can't (or don't want to) untangle by hand. The headline case is **unresolved tree** mode, where MrAnderson makes deeply nested, local copies of dependencies so that every subtree is isolated and the same library can appear many times without conflict. It's also the right tool when you don't want your library's dependencies leaking onto your users (Leiningen plugins are the classic example). And it's there if you just want to poke around the darker corners of dependency handling.

If you want the longer story, [Why MrAnderson exists](doc/rationale.md) covers the problem, an honest comparison to the alternatives, and when *not* to reach for it. New to Clojure or the JVM? The [glossary](doc/glossary.md) defines the jargon.

## Documentation

- [Why MrAnderson exists](doc/rationale.md) - the rationale, alternatives, and trade-offs.
- [How MrAnderson works](doc/design.md) - the architecture and the rewriting engine, for anyone changing the internals.
- [Glossary](doc/glossary.md) - plain-English definitions of the Clojure/JVM terms used throughout.
- [Contributing](CONTRIBUTING.md) - building, testing, and the self-hosting bootstrap.
- API docs and these articles render together on [cljdoc](https://cljdoc.org/d/thomasa/mranderson/CURRENT).

## Usage

MrAnderson is a [Leiningen](https://leiningen.org) plugin (Leiningen is a common Clojure build tool; `project.clj` is its config file). Put `[thomasa/mranderson "0.6.0"]` into the `:plugins` vector of your `project.clj`. You don't have to use Leiningen, though, see [Using MrAnderson without Leiningen](#using-mranderson-without-leiningen-toolsdeps--toolsbuild) below and [conjure-deps](https://github.com/Olical/conjure-deps) for an example.

Mark the dependencies you want inlined with the `^:inline-dep` metadata tag (only the marked ones get processed). For example:

```clojure
:dependencies [[org.clojure/clojure "1.5.1"]
               ^:inline-dep [org.clojure/tools.namespace "0.2.5"]
               ^:inline-dep [org.clojure/tools.reader "0.8.5"]
               ^:inline-dep [org.clojure/tools.nrepl "0.2.3"]]
```

Only the marked dependencies will be considered by MrAnderson. (Both `:inline-dep` and `:source-dep` meta tags are supported.)

Then run

    $ lein inline-deps

This retrieves the marked dependencies, rewrites them, and copies the result to `target/srcdeps`. Your own project files are copied and rewritten too, since their references to those dependencies have to point at the new inlined names.

The `plugin.mranderson/config` profile below puts `target/srcdeps` on the classpath, so the REPL, your tests, and the jar you build all use the inlined copies instead of the originals. Start a REPL in the context of your inlined dependencies with

    $ lein with-profile +plugin.mranderson/config repl

Or run your tests with them

    $ lein with-profile +plugin.mranderson/config test

Release locally

    $ lein with-profile +plugin.mranderson/config install

Release to Clojars

    $ lein with-profile +plugin.mranderson/config deploy clojars

Alternatively the modified dependencies and project files can be copied back to the source tree and stored in version control. In this case you don't need the above mentioned built-in Leiningen profile.

### Using MrAnderson without Leiningen (tools.deps / tools.build)

Clojure projects tend to use one of two build tools: Leiningen (`project.clj`,
above) or the Clojure CLI / tools.deps (`deps.edn`). They're alternatives, not
layers, so use whichever your project already has. Everything above is for
Leiningen; here's the same thing for tools.deps.

You don't need Leiningen to run MrAnderson. The `mranderson.core/inline-deps`
function is a plain, data-driven entry point that does the same work as the
`lein inline-deps` task. It's handy when your project is built with
[tools.build](https://clojure.org/guides/tools_build).

Add MrAnderson to a build alias in `deps.edn`:

```clojure
{:aliases
 {:build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.10"}
                 thomasa/mranderson {:mvn/version "0.6.0"}}
          :ns-default build}}}
```

and call it from your `build.clj`:

```clojure
(ns build
  (:require [mranderson.core :as mranderson]))

(defn inline-deps [_]
  (mranderson/inline-deps
   {:project-prefix "com.example.inlined"
    :source-paths   ["src"]
    :dependencies   '[[org.clojure/tools.namespace "1.5.1"]
                      [org.clojure/tools.reader "1.6.0"]]}))
```

Unlike the Leiningen plugin, every coordinate in `:dependencies` is inlined, so
there's no need to tag them with `^:inline-dep`. The shadowed sources land in
`target/srcdeps`, which you then put on the classpath when building your jar.

`inline-deps` doubles as a Clojure CLI tool function, so you can also invoke it
directly:

```
clojure -T:build inline-deps
```

See the docstring of `mranderson.core/inline-deps` for the full list of options.

## Config and options

### Two modes: resolved tree and unresolved tree

**Which one do you want?** Almost certainly the default, **resolved tree**. Reach for **unresolved tree** only when two of your dependencies need genuinely incompatible versions of a third one, since that's the conflict resolution can't paper over. The rest of this section explains the difference.

MrAnderson has **two modes**. In **resolved tree** mode (the default) it shadows a flat, conflict-resolved list of dependencies. In **unresolved tree** mode it works on the full, unresolved tree and builds a deeply nested directory structure from the marked dependencies.

In **unresolved tree** mode the same library (even the same version of the library) can occur multiple times in the unresolved dependency tree. When processing the tree MrAnderson walks it in a depth first order and creates a deeply nested directory structure and prefixes the namespaces and the references to them according to this directory structure.

Let's see [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl)'s list of dependencies in the project file (as it is at the time of writing this README):

```clojure
  :dependencies [[nrepl "0.6.0"]
                 ^:source-dep [cider/orchard "0.4.0"]
                 ^:source-dep [thunknyc/profile "0.5.2"]
                 ^:source-dep [mvxcvi/puget "1.1.0"]
                 ^:source-dep [fipp "0.6.15"]
                 ^:source-dep [compliment "0.3.8"]
                 ^:source-dep [cljs-tooling "0.3.1"]
                 ^:source-dep [cljfmt "0.6.4" :exclusions [org.clojure/clojurescript]]
                 ^:source-dep [org.clojure/tools.namespace "0.3.0-alpha4"]
                 ^:source-dep [org.clojure/tools.trace "0.7.10"]
                 ^:source-dep [org.clojure/tools.reader "1.2.2"]]
```

And the unresolved tree based on this list of dependencies for reference:

```
 [cljs-tooling "0.3.1"]
 [compliment "0.3.8"]
 [fipp "0.6.15"]
   [org.clojure/core.rrb-vector "0.0.13"]
 [org.clojure/tools.trace "0.7.10"]
 [cider/orchard "0.4.0"]
   [org.clojure/java.classpath "0.3.0"]
   [org.clojure/tools.namespace "0.3.0-alpha4"]
     [org.clojure/java.classpath "0.2.3"]
     [org.clojure/tools.reader "0.10.0"]
   [org.tcrawley/dynapath "0.2.5"]
 [cljfmt "0.6.4"]
   [com.googlecode.java-diff-utils/diffutils "1.3.0"]
   [org.clojure/tools.cli "0.3.7"]
   [org.clojure/tools.reader "1.2.2"]
   [rewrite-clj "0.6.0"]
     [org.clojure/tools.reader "0.10.0"]
   [rewrite-cljs "0.4.4"]
     [org.clojure/tools.reader "1.0.5"]
 [mvxcvi/puget "1.1.0"]
   [fipp "0.6.14"]
     [org.clojure/core.rrb-vector "0.0.13"]
   [mvxcvi/arrangement "1.1.1"]
 [org.clojure/tools.namespace "0.3.0-alpha4"]
   [org.clojure/java.classpath "0.2.3"]
   [org.clojure/tools.reader "0.10.0"]
 [thunknyc/profile "0.5.2"]
 [org.clojure/tools.reader "1.2.2"]
```

An example namespace of `[org.clojure/tools.reader "0.10.0"]` dependency of `[rewrite-clj "0.6.0"]` that is a dependency of `[cljfmt "0.6.4"]` will be prefixed like this:

```clojure
(ns ^{:mranderson/inlined true} cider.inlined-deps.cljfmt.v0v6v4.rewrite-clj.v0v6v0.toolsreader.v0v10v0.clojure.tools.reader.edn)
```

(Versions are encoded into the path with dots replaced by `v`, so `0.10.0` becomes `v0v10v0`. Dots already mean something in a namespace, so they can't be left as-is.)

and a reference to it in `cider.inlined-deps.cljfmt.v0v6v4.rewrite-clj.v0v6v0.rewrite-clj.reader` like this:

```clojure
(:require [cider.inlined-deps.cljfmt.v0v6v4.rewrite-clj.v0v6v0.toolsreader.v0v10v0.clojure.tools.reader
             [edn :as edn])
```

In the **resolved tree** mode MrAnderson flattens the resolved dependency tree out into a topologically ordered list and processes this ordered list. While processing MrAnderson prefixes all namespaces in the dependencies and the references to them. This also means that all dependencies, even transitive ones, are handled as first level dependencies: Maven's conflict resolution has already pinned every coordinate to a single version, so there's exactly one rename target per namespace.

And the resolved tree of the same project:

```
 [cljs-tooling "0.3.1"]
 [compliment "0.3.8"]
 [fipp "0.6.15"]
   [org.clojure/core.rrb-vector "0.0.13"]
 [org.clojure/tools.trace "0.7.10"]
 [cider/orchard "0.4.0"]
   [org.clojure/java.classpath "0.3.0"]
   [org.tcrawley/dynapath "0.2.5"]
 [cljfmt "0.6.4"]
   [com.googlecode.java-diff-utils/diffutils "1.3.0"]
   [org.clojure/tools.cli "0.3.7"]
   [rewrite-clj "0.6.0"]
   [rewrite-cljs "0.4.4"]
 [mvxcvi/puget "1.1.0"]
   [mvxcvi/arrangement "1.1.1"]
 [org.clojure/tools.namespace "0.3.0-alpha4"]
 [thunknyc/profile "0.5.2"]
 [org.clojure/tools.reader "1.2.2"]
```

The same namespace from `tools.reader` (note that there is only one version of it available in the dependency tree, `1.2.2`):

```clojure
(ns ^{:mranderson/inlined true} cider.inlined-deps.toolsreader.v1v2v2.clojure.tools.reader.edn)
```

and a reference to it in `cider.inlined-deps.rewrite-clj.v0v6v0.rewrite-clj.reader` looks like this:

```clojure
(:require [cider.inlined-deps.toolsreader.v1v2v2.clojure.tools.reader
             [edn :as edn])
```

In the **unresolved tree** mode the usual way of overriding dependencies, eg. putting a first level dependency in the project file with a newer version of a library, does not work. Also in this mode MrAnderson applies transitive dependency hygiene, meaning that it does not search and replace occurrences of a transitive dependency namespace in the project's own files (your own files shouldn't be reaching into someone else's transitive dependencies in the first place). To work around these limitations you can create a MrAnderson specific section in the project file and define overrides as such:

```clojure
:mranderson {:overrides {[mvxcvi/puget fipp] [fipp "0.6.15"]}}
```

Note that the key in the overrides map is a path to a dependency in the unresolved dependency tree and the value is the new dependency node.

In the same section you can instruct MrAnderson to expose certain transitive dependencies to the project's own source files as such:

```clojure
:mranderson {:expositions [[mvxcvi/puget fipp]]}
```

Here you have to provide a list of paths to dependencies to be exposed.

To use the **unresolved tree** mode you can either provide a flag in the above mentioned section

```clojure
:mranderson {:unresolved-tree true}
```

or you can provide the same flag on the command line:

    $ lein inline-deps :unresolved-tree true

The latter supersedes the former.

Again: in the **resolved tree** mode no transitive dependency hygiene is applied. Also the above described config options (*overrides* and *expositions*) don't take effect.

### Further config options

All the options below apply to the Leiningen plugin and can be provided via CLI
or the project file. The Leiningen-free `mranderson.core/inline-deps` function
takes the same set of options as a plain map, with two naming differences noted
below; see its docstring for the authoritative list.

| Option                   | Default                        |  Description | Example |
|--------------------------|--------------------------------|-------------|---------|
| project-prefix           | mranderson{rnd}                |  project specific prefix to use when shadowing            | `lein inline-deps :project-prefix cider.inlined-deps` |
| skip-javaclass-repackage | false                          |  If true [Jar Jar Links](https://code.google.com/p/jarjar/) won't be used to repackage java classes in dependencies. (The `inline-deps` function calls this option `:skip-repackage-java-classes`.)            | `lein inline-deps :skip-javaclass-repackage true`        |
| prefix-exclusions        | empty list                     |  List of prefixes which should not be processed in imports            |  `lein inline-deps :prefix-exclusions "[\"classlojure\"]"`  |
| watermark                | :mranderson/inlined            |  MrAnderson adds `watermark` as metadata to inlined namespaces. This allows tools like [cljdoc](https://cljdoc.org) to exclude inlined namespaces from a library's documented API. Cljdoc, for example, automatically excludes namespaces with any of `:mranderson/inlined`, `:no-doc`, `:skip-wiki` metadata. | `:mranderson {:watermark nil}` to switch off watermarking or provide your own keyword        |
| unresolved-tree          | false                          |  Switch between **unresolved tree** and **resolved tree** mode | `lein inline-deps :unresolved-tree true` |
| overrides                | empty list                     |  Defines dependency overrides in **unresolved tree** mode | `:mranderson {:overrides {[mvxcvi/puget fipp] [fipp "0.6.15"]}}` |
| expositions              | empty list                     |  Makes transitive dependencies available in the project's source files in **unresolved tree** mode | `:mranderson {:expositions [[mvxcvi/puget fipp]]}` |
| included-source-paths    | nil                            |  (Leiningen task only.) Determines which of the provided `:source-paths` (not `:test-paths`!) will be inlined. If `nil` or `:first`, the first source path (typically `"src"`) will be the only one to be processed. If set to `:source-paths`, all `:source-paths` will be processed. If set to a vector, that vector will be interpreted as the list of source dirs to be processed, as-is, ignoring the project's `:source-paths` value. |
| print-deps-tree          | false                          |  Print the dependency tree that would be inlined (the resolved tree, or the unresolved one in **unresolved tree** mode) and exit without inlining anything. | `lein inline-deps :print-deps-tree true` |
| report                   | false                          |  After inlining, print a per-file report of which namespaces' references were rewritten in each file, with reference counts. **Resolved tree** mode only. | `lein inline-deps :report true` |

## Prerequisites

Leiningen 2.9.1 or newer. MrAnderson itself needs that, but it doesn't constrain your project to any particular Java or Clojure version.

### Supported OSes and platforms

MrAnderson is tested and supported on Linux and macOS. Windows systems are not supported or tested against.

### Projects that use MrAnderson

- [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl)
- [refactor-nrepl](https://github.com/clojure-emacs/refactor-nrepl)
- [re-frame-10x](https://github.com/Day8/re-frame-10x)
- [iced-nrepl](https://github.com/liquidz/iced-nrepl)
- [conjure](https://github.com/Olical/conjure) via [conjure-deps](https://github.com/Olical/conjure-deps) (uses MrAnderson directly, not as a Leiningen plugin)

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full guide (setup, tests, linting,
CI, conventions) and [doc/design.md](doc/design.md) for how the engine works.

MrAnderson is a little unusual to build: it inlines its own dependencies using
itself, so it depends on itself as a Leiningen plugin. The `Makefile` wraps that
bootstrap. See [CONTRIBUTING.md](CONTRIBUTING.md) for the targets and the full
workflow, and [doc/design.md](doc/design.md) for how the engine works.

## Related project

A friendly Leiningen wrapper around MrAnderson lives at [lein-isolate](https://github.com/xsc/lein-isolate).

## Credits

- The engine of namespace renaming/moving `mranderson.move` although heavily modified now is based on Stuart Sierra's `clojure.tools.namespace.move` namespace from [tools.namespace](https://github.com/clojure/tools.namespace).
- Some ideas around namespace renaming/moving were borrowed from @expez, my co-maintainer for [refactor-nrepl](https://github.com/clojure-emacs/refactor-nrepl), in their fabulous work on the `rename-file-or-dir` feature.
- @cichli did a round of profiling and performance/parallelisation fixes on mranderson which I took inspiration from
- Had amazing feedback, conversations around MrAnderson and dependencies with @bbatsov (MrAnderson's main client), @reborg, @SevereOverfl0w, @andrewmcveigh. Really grateful for the community and these nice people in particular.
- Shout out for the contributors specifically @bbatsov -- finalising 0.6.0 and taking care of 0.6.1, @lread, @vemv

## License

Copyright © 2014-2026 Benedek Fazekas & contributors

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
