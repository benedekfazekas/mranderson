# Glossary

MrAnderson sits at the bottom of the JVM/Clojure stack, so its docs lean on a
fair amount of ecosystem vocabulary. If you're new to Clojure or the JVM, here's
the jargon in plain English. Terms are roughly ordered from "you need this to
understand the README" to "you need this to hack on the internals."

**Classpath.** The single global list of all the compiled code available to a
running JVM program. Think Python's `sys.path`, with one crucial difference:
there's room for only *one* version of any given class. That one-version rule is
the whole reason MrAnderson exists.

**Dependency / transitive dependency.** A library your project pulls in is a
dependency. A *transitive* dependency is a dependency of a dependency, something
you didn't ask for directly but that got pulled in because something you do use
needs it.

**Diamond dependency / diamond conflict.** When two of your dependencies both
depend on a third one, but need *incompatible* versions of it. Drawn out, the
graph looks like a diamond (you at the top, the shared library at the bottom).
Because the classpath holds one version per class, this is the conflict that
nothing short of inlining can fully resolve.

**Inlining / shading / vendoring.** Three names for the same technique: copy a
dependency's code into your own project under a private name, rewrite every
reference to it, and ship that copy. Your private copy can't collide with anyone
else's version. This is what MrAnderson does. (The Java world usually says
"shading"; "vendoring" is common elsewhere; MrAnderson's command is
`inline-deps`, so these docs lead with "inlining.")

**`:exclusions`.** A build-tool feature that tells the dependency resolver to
drop a particular transitive dependency. The first thing to try when you have a
conflict; inlining is what you reach for when exclusions aren't enough.

**Namespace.** Clojure's unit of code organization, roughly a module. The
important part for MrAnderson: a namespace is identified by its *name* (like
`clojure.string`), and Clojure resolves that name at runtime, not by file path.
So renaming a namespace means changing the name everywhere it's written, not just
moving a file.

**`ns` form / `require` / `:import` / `:use`.** The `ns` form is the declaration
at the top of a Clojure file that names the namespace and lists what it pulls in
(its `require`s for other Clojure namespaces, its `:import`s for Java classes).
These are the references MrAnderson has to rewrite.

**Leiningen (`lein`, `project.clj`).** The long-standing Clojure build tool.
`project.clj` is its config file (think `package.json`). MrAnderson ships as a
Leiningen plugin.

**tools.deps / Clojure CLI (`deps.edn`, tools.build).** The other common Clojure
build toolchain, an alternative to Leiningen, not a layer on top of it.
`deps.edn` is its config file. MrAnderson's `inline-deps` function works here
without Leiningen.

**jar / uberjar.** A jar is a zip of compiled JVM code, the unit you publish and
depend on (a library is distributed as a jar). An *uberjar* (or "fat jar") bundles
your app and *all* its dependencies into one runnable jar. Uberjar tools can shade
dependencies at assembly time, but that's for applications; MrAnderson is for
libraries that ship a normal jar with some dependencies quietly inlined.

**Maven / aether / pomegranate / Clojars.** Maven is the JVM's dependency and
artifact system (it resolves versions and downloads jars). aether is the library
that talks to Maven repositories; pomegranate is the Clojure wrapper MrAnderson
uses for resolution. Clojars is the community package registry for Clojure (like
PyPI or npm).

**Conflict resolution.** The step where Maven, given a dependency graph that asks
for several versions of the same library, picks one. The *resolved* tree is the
result; the *unresolved* tree is the original graph before that collapse.

**REPL / nREPL / middleware.** A REPL is an interactive Clojure prompt. nREPL is
the networked server behind editor integrations (the thing your editor talks to).
*Middleware* plugs into nREPL to add features, and it gets loaded into the
*user's* running process, alongside the user's own dependencies, which is exactly
why a tool that drags in conflicting versions is such a problem. cider-nrepl and
refactor-nrepl are nREPL middleware, and MrAnderson's main clients.

**AOT (ahead-of-time compilation).** Compiling Clojure source to JVM `.class`
files before running, instead of at load time. AOT interacts badly with inlining
in a couple of ways; see [when not to use it](rationale.md#when-not-to-use-it).

**deftype / defrecord.** Clojure forms that generate a Java class from Clojure
code. Relevant because a `deftype` class is created when the source compiles, so
(unlike a bundled Java `.class`) it has no separate file to relocate and moves
with its namespace.

**Reader conditionals / `.cljc`.** A `.cljc` file runs on both Clojure (JVM) and
ClojureScript. Reader conditionals (`#?(:clj ... :cljs ...)`) select different
code per platform. MrAnderson has to rewrite each branch to the right place.

**Type hint.** An annotation like `^String` that tells the compiler a value's
Java type, so it can avoid *reflection* (looking the type up at runtime, which is
slow). Clojure emits a warning when it has to use reflection; this project's CI
treats that warning as an error.

**Topological order.** An ordering of a dependency graph where each item comes
before the things that depend on it. MrAnderson builds one when flattening the
resolved tree.

**jarjar (Jar Jar Links).** A tool that relocates Java classes inside a jar under
a new package name. MrAnderson uses it for the `.class` files a dependency
bundles, while doing the Clojure side as a source rewrite.

**Watermark.** The `^{:mranderson/inlined true}` metadata MrAnderson stamps on
every inlined namespace, so tools like cljdoc can tell a vendored copy from the
library's real API.
