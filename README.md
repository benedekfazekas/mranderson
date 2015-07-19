# MrAnderson

Dependencies as source: used as if part of the project itself.

Somewhat node.js & npm style dependency handling as a leiningen plugin.

**Fancy words: 'npm style dependency handling' but what is this project is really about?**

It is an inlining tool which inlines your project's dependencies at packaging time. It automatically retrieves and prefixes your dependencies (both clojure source and java class files) and munges your clojure files -- mainly the namespace declaration but not only -- accordingly.

## Prerequisites

**IMPORTANT** You need to install leiningen version **2.5.0** at least for this plugin if you want to use the built in profile (see below for explanation). Basic functionality (eg `lein source-deps`) still works though.

## Usage

Put `[thomasa/mranderson "0.4.4"]` into the `:plugins` vector of your project.clj.

Additionally you also need to mark some of the dependencies in your dependencies vector in the project's `project.clj` with `^:source-dep` meta tag. For example:

```clojure
:dependencies [[org.clojure/clojure "1.5.1"]
               ^:source-dep [org.clojure/tools.namespace "0.2.5"]
               ^:source-dep [org.clojure/tools.reader "0.8.5"]
               ^:source-dep [org.clojure/tools.nrepl "0.2.3"]]
```

Now you are ready to run:

    $ lein source-deps

this retrieves dependencies and creates a deeply nested directory structure for them in `target/srcdeps` directory. It also munges all clojure source files accordingly. More over it uses [Jar Jar Links](https://code.google.com/p/jarjar/) to repackage your java class files dependencies if any.

If you don't want mranderson to repackage your java dependencies you can opt out by passing `:skip-javaclass-repackage true` as a parameter to `source-deps` task.

After that you can run your tests or your repl with:

    $ lein with-profile +plugin.mranderson/config repl

    $ lein with-profile +plugin.mranderson/config test

note the plus sign before the leiningen profile.

If you want to use mranderson while developing locally with the repl the source has to be modified in the target/srcdeps directory.

When you want to release locally:

    $ lein with-profile plugin.mranderson/config install

to clojars:

    $ lein with-profile +plugin.mranderson/config deploy clojars

If you want to change, update your dependencies just edit your `project.clj` file the usual way and run

    $ lein clean

and then again

    $ lein source-deps

and you are good to go.

**note** you should not mark clojure itself as a source dependency: there is a limit for everything.

## Under the hood

There is not much magic there but simple modifing the source files as strings [tools.namespace](https://github.com/clojure/tools.namespace) style; see specially `clojure.tools.namespace.move` namespace. Also additionally some more source file munging is done for prefixes, some deftypes and the like.

It also uses [Jar Jar Links](https://code.google.com/p/jarjar/) to repackage your java class files dependencies if any.

A bit of additional magic happens when you use the built in profile: it actively switches on the leiningen middleware also built into the plugin. The middleware AOT compiles some clojure sources and removes dependencies marked with `^:source-deps` from the dependency list. So they won't show up in the generated pom file and so on.

## Rationale

Some might argue that the clojure (and java) way of dependency handling is broken. Nonetheless what npm does for node.js namely nested, local dependencies just feels right. And altough javascript land is a different world the same can be used at least for certain projects in clojure as well. Perhaps not all but some projects, specially the ones related to tooling and commons like libraries which a lot of other projects are depending on. These can benefit from this style of dependency handling.

**MrAnderson?! Why?**

At the end he really gets back to the source, does not he?

## License

Copyright © 2014 Benedek Fazekas

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
