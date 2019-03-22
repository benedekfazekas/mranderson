[![CircleCI](https://circleci.com/gh/benedekfazekas/mranderson/tree/0.5.x.svg?style=svg)](https://circleci.com/gh/benedekfazekas/mranderson/tree/0.5.x)

# MrAnderson

MrAnderson is a dependency inlining and shadowing tool. It isolates the project's dependencies so they can not interfere with other libraries' dependencies.

## Usage

MrAnderson is a leiningen plugin. Put `[thomasa/mranderson "0.5.0-SNAPSHOT"]` into the `:plugins` vector of your project.clj.

Mark some of the dependencies in your dependencies vector in the project's `project.clj` with `^:source-dep` meta tag. For example:

```clojure
:dependencies [[org.clojure/clojure "1.5.1"]
               ^:source-dep [org.clojure/tools.namespace "0.2.5"]
               ^:source-dep [org.clojure/tools.reader "0.8.5"]
               ^:source-dep [org.clojure/tools.nrepl "0.2.3"]]
```

Only the marked dependencies will be considered by MrAnderson.

Then run

    $ lein source-deps

This retrieves and modifies the marked dependencies and copies them to `target/srcdeps` together with the modified project files -- their references to the dependencies need to change too.

After this you can start the REPL in the context of your inlined dependencies

    $ lein with-profile +plugin.mranderson/config repl

Or run your tests with them

    $ lein with-profile +plugin.mranderson/config test

Release locally

    $ lein with-profile plugin.mranderson/config install

Release to clojars

    $ lein with-profile +plugin.mranderson/config deploy clojars

Alternatively the modified dependencies and project files can be copied back to the source tree and stored in version control. In this case you don't need the above mentioned built in leiningen profile.

## Config and options

### Two modes: unresolved dependency tree and shadowing only

MrAnderson has **two modes**. It can either work on an unresolved dependency tree (the default) or only shadow a list of dependencies based on a resolved dependency tree.

Working on an **unresolved dependency tree** means that the same library -- even the same version of the library -- can occur multiple times in the dependency tree. When processing the tree MrAnderson walks it in a depth first order and creates a deeply nested directory structure and prefixes the namespaces and the references to them according to this directory structure.

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

and a reference to it in `cider.inlined-deps.cljfmt.v0v6v4.rewrite-clj.v0v6v0.rewrite-clj.reader` like this:

```clojure
(:require [cider.inlined-deps.cljfmt.v0v6v4.rewrite-clj.v0v6v0.toolsreader.v0v10v0.clojure.tools.reader
             [edn :as edn])
```

In the **shadowing only** mode MrAnderson flattens the resolved dependency tree out into a topoligically ordered list and processes this ordered list. While processing MrAnderson refixes all namespaces in the dependencies and the references to them. This also means that all dependencies even transient ones are handled as first level dependencies as they can only occur once in a resolved dependency tree.

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

The same namespace from `tools.reader` -- note that there is only one version of it available in the dependency tree `1.2.2`:

```clojure
(ns ^{:mranderson/inlined true} cider.inlined-deps.toolsreader.v1v2v2.clojure.tools.reader.edn)
```

and a reference to it in `cider.inlined-deps.rewrite-clj.v0v6v0.rewrite-clj.reader` looks like this:

```clojure
(:require [cider.inlined-deps.toolsreader.v1v2v2.clojure.tools.reader
             [edn :as edn])
```

In the **unresolved dependency tree** mode the usual way of overriding dependencies, eg. putting a first level dependency in the project file with a newer version of a library does not work. Also in this mode MrAnderson applies transient dependency hygiene meaning that it does not search and replace occurrances of a transient depedency namespace in the project's own files. To work around these limitations you can create a MrAnderson specific section in the project file and define overrides as such:

```clojure
:mranderson {:overrides {[mvxcvi/puget fipp] [fipp "0.6.15"]}}
```

Note that the key in the overrides map is a path to a dependency in the unresolved dependency tree and the value is the new depedency node.

In the same section you can instruct MrAnderson to expose certain transient dependencies to the project's own source files as such:

```clojure
:mranderson {:expositions [[mvxcvi/puget fipp]]}
```

Here you have to provide a list of paths to dependencies to be exposed.

To use the **shadowing only** mode you can either provide a flag in the above mentioned section

```clojure
:mranderson {:shadowing-only true}
```

or you can provide the same flag on the command line:

    $ lein source-deps :shadowing-only true

The latter supersedes the former.

Again: in the **shadowing only** mode no transient dependency hygiene is applied. Also the above described config options (*overrides* and *expositions*) don't take effect.

### Further config options

| Option                   | Default                        | CLI or project.clj    | Description | Example |
|--------------------------|--------------------------------|-----------------------|-------------|---------|
| project-prefix           | mranderson{mranderson version} | CLI                   | project pecific prefix to use when shadowing            | `lein source-deps :project-prefix cider.inlined-deps` |
| skip-javaclass-repackage | false                          | CLI                   | If true [Jar Jar Links](https://code.google.com/p/jarjar/) won't be used to repackage java classes in dependencies            | `lein source-deps :skip-javaclass-repackage true`        |
| prefix-exclusions        | empty list                     | CLI                   | List of prefixes which should not be processed in imports            |  `lein source-deps :prefix-exclusions "[\"classlojure\"]"`  |
| watermark                | :mranderson/inlined            | project.clj           | When processing namespaces in dependencies MrAnderson marks them with a meta so inlined namespaces can be identified. Helpful for tools like [cljdoc](https://cljdoc.org)            | `:mranderson {:watermark nil}` to switch off watermarking or provide your own keyword        |
| shadowing-only           | false                          | CLI, project.clj      | Switch between **unresolved tree** and **shadowing only** mode | `lein source-deps :shadowing-only true` |
| overrides                | empty list                     | project.clj           | Defines dependency overrides in **unresolved tree** mode | `:mranderson {:overrides {[mvxcvi/puget fipp] [fipp "0.6.15"]}}` |
| expositions              | empty list                     | project.clj           | Makes transient dependencies available in the project's source files in **unresolved tree** mode | `:mranderson {:expositions [[mvxcvi/puget fipp]]}` |

## Prerequisites

Leiningen 2.8.3 or above. For MrAnderson to work, does not mean your project is restricted to a java or clojure version.

## Is it good? Should I use it?

Yes and yes. Use it if you have depedency conflicts and don't care to solve them. Or if you don't want your library's dependencies to interfere with the dependencies of your users' (leiningen plugins is a good example here). Or if you want to explore a bit more in the hellish land of dependency handling.

### Projects that use MrAnderson

- [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl)
- [refactor-nrepl](https://github.com/clojure-emacs/refactor-nrepl)
- [re-frame-10x](https://github.com/Day8/re-frame-10x)

### Libraries that work with MrAnderson

Here is a list of libraries (with specific versions) that MrAnderson can inline.

TODO

### Libraries that MrAnderson chokes on (and why)

Here is a list of libraries that MrAnderson has problems with. This does not mean those libraries do anything wrong it is more likely MrAnderson does not understand something they are doing. Hopefully it will some day.

TODO

## Related project

A really nice wrapper of mranderson can be found [here](https://github.com/xsc/lein-isolate).

## Future plans (maybe create issues rather)

TODO

## Credits

- The engine of namespace renaming/moving `mranderson.move` although heavily modified now is based on Stuart Sierra's `clojure.tools.namespace.move` namespace from [tools.namespace](https://github.com/clojure/tools.namespace).
- Some ideas around namespace renaming/moving was borrowed from @expez my co-maintainer for [refactor-nrepl](https://github.com/clojure-emacs/refactor-nrepl) in their fabolous work of `rename-file-or-dir` feature.
- @cichli did a round of profiling and perfromance/parallelisation fixes on mranderson which I took insipiration from
- Had amazing feedback, conversations around MrAnderson and dependencies with @bbatsov (MrAnderson's main client), @reborg, @SevereOverfl0w, @andrewmcveigh. Really grateful for the community and these nice people in particular.

## License

Copyright © 2014-2019 Benedek Fazekas

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
