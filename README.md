[![CircleCI](https://circleci.com/gh/benedekfazekas/mranderson/tree/0.5.x.svg?style=svg)](https://circleci.com/gh/benedekfazekas/mranderson/tree/0.5.x)

# MrAnderson

MrAnderson is a dependency inlining and shadowing tool. It isolates the project's dependencies so they can not interfere with other libraries' dependencies.

## Usage

MrAnderson is a leiningen plugin. Put `[thomasa/mranderson "0.5.0"]` into the `:plugins` vector of your project.clj.

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

Alternatively the modified dependencies and project files can be copied back to the source tree and stored in version control. This case you don't need the above mentioned built in leiningen profile.

### Config and options

MrAnderson has two modes. It can either work on an unresolved dependency tree (the default) or only shadow a list of dependencies based on a resolved dependency tree.

Working on an unresolved dependency tree means that the same library -- even the same version of the library -- can occur multiple times in the dependency tree. When processing the tree MrAnderson creates a deeply nested directory structure and prefixes the namespaces and the references to them according to this directory structure. Note that the usual way of overriding dependencies, eg. putting a first level dependency in the project file with a newer version of a library does not work in this mode. Also note that in this mode MrAnderson applies transient dependency hygiene meaning that it does not search and replace occurrances of a transient depedency namespace in the project's own files. To work around these limitations you can create a MrAnderson specific section in the project file and define overrides as such:

```clojure
:mranderson {:overrides {[mvxcvi/puget fipp] [fipp "0.6.15"]}}
```

Note that the key in the overrides map is a path to a dependency in the unresolved dependency tree and the value is the new depedency node.

In the same section you can instruct MrAnderson to expose certain transient dependencies to the project's own source files as such:

```clojure
:mranderson {:expositions [[mvxcvi/puget fipp]]}
```

Here you have to provide a list of paths to dependencies to be exposed.

To use the shadowing only mode you can either provide a flag in the above mentioned section

```clojure
:mranderson {:shadowing-only true}
```

or you can provide the same flag on the command line:

    $ lein source-deps :shadowing-only true

The latter superseeds the former.

In the shadowing only mode MrAnderson works on a resolved dependency tree. Flattens the dependency tree out into a list and prefixes all namespaces and the references to them. This also means that all dependencies, even transient ones are handled as first level dependencies as they can only occur once in a resolved dependency tree, therefore no transient dependency hygiene is applied in this mode. Also the above described config options (overrides and expositions) don't take effect.

Further config options

| Option                   | Default                        | CLI or project.clj    | Description | Example |
|--------------------------|--------------------------------|-----------------------|-------------|---------|
| project-prefix           | mranderson{mranderson version} | CLI                   | project pecific prefix to use when shadowing            | `lein source-deps :project-prefix cider.inlined-deps` |
| skip-javaclass-repackage | false                          | CLI                   | If true [Jar Jar Links](https://code.google.com/p/jarjar/) won't be used to repackage java classes in dependencies            | `lein source-deps :skip-javaclass-repackage true`        |
| prefix-exclusions        | empty list                     | CLI                   | List of prefixes which should not be processed in imports            |  `lein source-deps :prefix-exclusions "[\"classlojure\"]"`  |
| watermark                | :mranderson/inlined            | project.clj           | When processing namespaces in dependencies MrAnderson marks them with a meta so inlined namespaces can be identified. Helpful for tools like [cljdoc](https://cljdoc.org)            | `:mranderson {:watermark nil}` to switch off watermarking or provide your own keyword        |

### Prerequisites

Leiningen 2.8.3 or above. For MrAnderson to work, does not mean your project is restricted to a java or clojure version.

## Is it good? Should I use it?

Yes and yes. Use it if you have depedency conflicts and don't care to solve them. Or if you don't want your library's dependencies to interfere with the dependencies of your users' (leiningen plugins is a good example here).

### Projects that use MrAnderson

- [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl)
- [refactor-nrepl](https://github.com/clojure-emacs/refactor-nrepl)
- [re-frame-10x](https://github.com/Day8/re-frame-10x)

### Libraries that work with MrAnderson

Here is a list of libraries (with specific versions) that MrAnderson can inline.

TODO

### Libraries that MrAnderson chokes on (and why)

Here is a list of libraries that MrAnderson has problems with. This does not mean those libraries do anything wrong it is more likely MrAnderson does not understand something they are doing, hopefully yet...

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
