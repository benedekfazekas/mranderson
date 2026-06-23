# Why MrAnderson exists

## The problem

The JVM has one classpath and one version of every class on it. Clojure inherits
that. If your project pulls in library A, which needs `some.lib 1.0`, and library
B, which needs `some.lib 2.0`, and those two versions aren't compatible, you have
a problem that no amount of `:exclusions` will fully solve. Maven's conflict
resolution will pick one version and hope for the best. Sometimes that's fine.
Sometimes you get a `NoSuchMethodError` at three in the morning.

For *applications* this is usually annoying but manageable: you control the whole
dependency set, so you can pin versions and exclude things until it works. For
*libraries* it's worse, because your dependencies leak onto your users. If you
write a library that depends on `some.lib 1.0`, every project that uses your
library now has an opinion about `some.lib` forced on it, and if the user needs
`2.0`, you've just started a fight.

This is especially painful for developer tooling. nREPL middleware (cider-nrepl,
refactor-nrepl) gets loaded into *someone else's* running process, alongside
*their* dependencies. A REPL tool that drags in its own `tools.reader` or
`rewrite-clj` and clobbers the user's version is a tool that breaks the user's
project. That's the itch MrAnderson was built to scratch, and cider-nrepl has
been its main client for years.

## The fix: inline and shadow

The way out is *inlining* (also called shading or vendoring): take the
dependency, copy it into your own project under a private name, rewrite every
reference, and ship that. Now your copy of `some.lib` is called something like
`yourproject.inlined-deps.some.lib`, it can't collide with anyone else's
`some.lib`, and your users never even know it's there.

The catch in Clojure specifically is that namespaces are resolved by *name* at
runtime, not by file path. You can't just move files and rename packages the way
a Java bytecode shader does. You have to rewrite the names inside the source: the
`ns` forms, the `require`s, the `import`s, the type hints, the fully-qualified
symbols, the `(load ...)` paths. And if the dependency also bundles Java
`.class` files, you have to relocate those too, with a separate tool, and make
the rewritten source agree with where they landed. MrAnderson does all of that.
The [design doc](design.md) walks through how.

## What about the alternatives?

MrAnderson occupies a specific niche, source-level inlining for libraries, and
most of the obvious alternatives are solving a different problem.

### Just use `:exclusions` and pin versions

This is the right answer most of the time, and you should try it first. It breaks
down only when you have a genuine diamond conflict: two dependencies that need
*incompatible* versions of a third, where no single version satisfies both. Then
no exclusion helps, because there's no good version to keep. That's the point
where inlining earns its keep.

### Uberjar shading (depstar, tools.build `uber`, Maven Shade Plugin)

These relocate packages when you build an **application** uberjar. They run at
assembly time, operate on bytecode/jar contents, and produce a single fat jar
with the conflicts shaded away. Great for apps. They don't help a **library**
that wants to publish a normal jar with a few dependencies quietly inlined,
because there's no uberjar in the picture: you're deploying a library artifact
that other people will pull in as a dependency. MrAnderson produces inlined
*source* that goes into your ordinary library jar.

There's also the Clojure-runtime wrinkle: bytecode relocation can rename compiled
class names, but Clojure resolves namespaces from *strings* at runtime, and those
strings aren't reliably where a bytecode shader looks. So a pure jarjar/shade
pass tends to leave the namespace references behind, which is brittle at best.
MrAnderson uses jarjar for the Java `.class` files but does the Clojure side as a
source rewrite.

### shadow-cljs

Unrelated, despite the name. shadow-cljs is a ClojureScript build tool; the
"shadow" isn't about shading dependencies.

### lein-isolate

A friendly Leiningen wrapper around MrAnderson itself. If you're on Leiningen and
want a gentler interface, [lein-isolate](https://github.com/xsc/lein-isolate)
wraps it nicely.

## When *not* to use it

Honest version: inlining is a power tool, and it has sharp edges.

- **If plain `:exclusions` work, use those.** Inlining is more machinery and more
  ways to go wrong.
- **The inlined namespaces are ugly** (`yourproj.inlined-deps.foo.v1v2v3.foo.core`)
  and they show up in stack traces. That's the price of isolation.
- **It's a textual rewrite, so it can't follow indirection.** A namespace named
  by a runtime-computed string, `(require (symbol ...))`, `resolve`/`find-ns` on
  a built-up name, or a name read out of a resource file, is invisible to the
  rewriter. The reference stays pointed at the original, un-inlined name and blows
  up at runtime.
- **AOT and macros are the classic trap.** If you ahead-of-time compile and a
  macro from an inlined dependency expands to a fully-qualified symbol that the
  rewriter didn't catch (syntax-quoted symbols are the usual culprit), you get a
  `ClassNotFoundException` no test saw coming. `:gen-class` with a fixed `:name`
  has the same flavour: the generated class name is a config string, not a
  namespace reference, so it won't be prefixed.
- **Unresolved-tree mode trades version conflicts for type-identity
  fragmentation.** Two inlined copies of the same library are two different sets
  of classes; instances from one are not instances of the other. Fine when the
  conflicting library is each subtree's private business, a problem when it shows
  up in a public API.
- **It's tested and supported on Linux and macOS, not Windows.**

None of this is exotic, but it's the stuff that bites. Most code inlines cleanly.
If you've read all that and you still have two libraries fighting over a third,
or you're shipping tooling that has to coexist with whatever the user already
has on their classpath, MrAnderson is for you.
