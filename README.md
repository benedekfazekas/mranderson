# MrAnderson

Dependencies as source: used as if part of the project itself.

Somewhat node.js & npm style dependency handling.

## Prerequisites

**IMPORTANT** You need to install leiningen version **2.5.0** at least for this plugin if you want to use the built in profile (see below for explanation). Basic functionality (eg `lein source-deps`) still works though.

## Usage

Put `[thomasa/mranderson "0.1.0-SNAPSHOT"]` into the `:plugins` vector of your project.clj.

Additionally you also need to mark some of the dependencies in your dependencies vector in the project's `project.clj` with `^:source-dep` meta tag. For example:

```clojure
:dependencies [[org.clojure/clojure "1.5.1"]
               ^:source-dep [org.clojure/tools.namespace "0.2.5"]
               ^:source-dep [org.clojure/tools.reader "0.8.5"]
               ^:source-dep [org.clojure/tools.nrepl "0.2.3"]]
```

Now you are ready to run:

    $ lein source-deps

**What happens here?**

The plugin basically retrieves the dependencies you marked (and their transitive dependencies) and builds a nested tree of directories in `target/srcdeps` directory with the retrieved files. It also copies the project's sources under this directory and modifies both the project source files and the dependency source files so their namespace names are all reflecting the newly created nested directory structure.

**So far so good, but what's then?**

Yes, this is only half way. Now you can of course still work with your original source tree but the plugin also provides you a built in profile which enables you to work with the munged sourcetree including your dependencies. For example you can (and should) run your tests with the modified source tree:

    $ lein with-profile plugin.mranderson/config test

This does not stop here of course you can start up your repl with the modified source tree too of course.

**Ok I can play with the modified source but how do I release?**

The usual way only use the above mentioned built in profile when you run `jar`, `install` and friends.

**What happens when I upgrade one of the depencies?**

Easy: run

    $ lein clean

change your dependencies and then again

    $ lein source-deps

and you are good to go.

**note** you should not mark clojure itself as a source dependency: there is a limit for everything.

## Under the hood

There is not much magic there but simple modifing the source files as strings [tools.namespace](https://github.com/clojure/tools.namespace) style; see specially `clojure.tools.namespace.move` namespace. Also additionally some more source file munging is done for prefixes, some deftypes and the like.

A bit of additional magic happens when you use the built in profile: it actively switches on the leiningen middleware also built into the plugin. The middleware AOT compiles some clojure sources and removes dependencies marked with `^:source-deps` from the dependency list. So they won't show up in the generated pom file and so on.

## Rationale

Some might argue that the clojure (and java) way of dependency handling is broken. Nonetheless what npm does for node.js namely nested, local dependencies just feels right. And altough javascript land is a different world the same can be used at least for certain projects in clojure as well. Perhaps not all but some projects, specially the ones related to tooling and commons like libraries which a lot of other projects are depending on. These can benefit from this style of dependency handling.

**MrAnderson?! Why?**

At the end he really gets back to the source, does not he?

## License

Copyright © 2014 Benedek Fazekas

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
