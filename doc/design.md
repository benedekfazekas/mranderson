# How MrAnderson works

This is the guided tour of the internals: what happens when you run `lein
inline-deps`, why it's built the way it is, and where to look in the code. If you
just want to *use* MrAnderson, the [README](../README.md) is the place to start.
If you're about to change the engine, read this first.

## The one-sentence version

MrAnderson takes a set of dependencies, copies their source under a prefix
unique to your project, rewrites every namespace and every reference to it so the
copies can't collide with anyone else's, repackages any bundled Java `.class`
files the same way, and drops the result in `target/srcdeps`.

The trick that makes it more than a glorified search-and-replace: Clojure
resolves namespaces by *name* at runtime, not by file path, so a library can't
just rename files on disk and call it done. Every place a name is written down
has to move with it, and there are more of those than you'd think: `ns` forms,
`require`s, fully-qualified symbols, `(load "...")` resource paths, and (for the
Java classes a dependency might bundle, a separate resolution mechanism)
`import`s and type hints. Getting that right across real-world code (reader
conditionals, dashes that become underscores in Java packages, deftype classes,
resource paths) is most of what the code is about.

It also has a hard limit worth stating up front: this is a *textual* rewrite, so
it can only move names it can see. A namespace named by a runtime-computed string
(`(require (symbol (str "foo." x)))`, `resolve`/`find-ns` on a built-up name, a
name read out of an EDN resource) is invisible to it. Most code doesn't do that.
Code that does will not inline cleanly, and the [rationale](rationale.md#when-not-to-use-it)
spells out the other sharp edges.

## The pipeline

The entry point is `mranderson.core/mranderson`. Everything else hangs off it. At
a high level:

```
dependencies
   │  resolve  (mranderson.dependency.resolver, via pomegranate/aether)
   ▼
resolved tree  ──expand──►  unresolved tree
   │                              │
   │  (resolved-tree mode)        │  (unresolved-tree mode)
   ▼                              ▼
unzip each artifact into target/srcdeps
   │
   ▼
move namespaces + rewrite references   (mranderson.move)
   │
   ▼
repackage bundled Java .class files    (jarjar, mranderson.util)
   │
   ▼
target/srcdeps  +  the project's own (rewritten) sources
```

### 1. Resolve

`mranderson.dependency.resolver` wraps [pomegranate](https://github.com/clj-commons/pomegranate)/aether.
`resolve-source-deps` gives you the *resolved* tree (Maven's conflict resolution
has already run, so every coordinate is pinned to a single version, even if it
still shows up in more than one branch). `expand-dep-hierarchy` re-expands it into
the *unresolved* tree, where the same library, even the same version, can appear
in many places with different versions side by side. Those two trees feed the two modes below.

`mranderson.dependency.tree` is the toolbox for walking these trees:
`walk-deps` (depth-first visit), `walk-dep-tree` (pre/post-order with paths
threaded down, used by unresolved mode), `walk-ordered-deps` (a flat,
topologically ordered walk used by resolved mode), `topological-order`, and
`evict-subtrees` (used to drop Clojure itself and a couple of other "this is part
of the platform, don't inline it" roots).

### 2. Unzip

Each artifact's jar is unzipped into `target/srcdeps`. `remove-invalid-duplicates!`
(see [#44](https://github.com/benedekfazekas/mranderson/issues/44)) deals with
libraries that ship the same namespace in two places (riddley is the classic),
which would otherwise confuse the file-moving step.

### 3. Move namespaces and rewrite references

This is the heart of it, and it has its own section below.

### 4. Repackage Java classes

If the dependencies bundle compiled `.class` files (http-kit, diffutils,
claypoole, and friends), renaming Clojure namespaces isn't enough: the classes
themselves live in packages that could collide too. MrAnderson hands those to
[Jar Jar Links](https://github.com/pantsbuild/jarjar) (vendored under
`java-src/mranderson/util/`), which relocates them under a prefix derived from
your project's name and version (`clean-name-version`, e.g. `myproj010`). The
source references to those classes are rewritten to match in `prefix-java-imports!`.

One subtlety bit users for years: the Java repackaging prefix (from name+version)
is *not* the same as the namespace prefix (`:project-prefix`). A reference to a bundled Java class therefore has to end up
under the jarjar prefix, while the Clojure namespaces around it end up under the
namespace prefix. See "The Java/namespace overlap" below.

## The two modes

MrAnderson has two ways of laying out the inlined tree, and they exist for
genuinely different problems.

### Resolved-tree mode (the default)

Maven's resolution has already collapsed the tree, so each dependency appears
exactly once. MrAnderson flattens it into a topologically ordered list and gives
every namespace a single new home:

```
cider.inlined-deps.toolsreader.v1v2v2.clojure.tools.reader.edn
```

Because the old-name-to-new-name mapping is one-to-one and global, the reference
rewriting is conceptually simple: build one big rename map and apply it
everywhere. (This is exactly what the engine does now, and it's why resolved mode
got dramatically faster. See "The rewriting engine".)

The list is still produced in topological order, but with the one-pass global
rename map that ordering no longer matters for correctness, the way it did in the
old per-namespace design where a dependency had to be processed before the things
that referenced it. It's effectively vestigial for resolved mode now; don't read
load-bearing meaning into it.

### Unresolved-tree mode

Here MrAnderson works on the *un*resolved tree, where the same library can appear
many times, and nests each dependency under its parent:

```
cider.inlined-deps.cljfmt.v0v6v4.rewrite-clj.v0v6v0.toolsreader.v0v10v0.clojure.tools.reader.edn
```

Every subtree is fully isolated, so two dependencies that each need a different
(conflicting) version of a third library can both have their own copy. This is
the mode you reach for when you have a real diamond conflict that resolution
can't paper over.

It buys that isolation with a real trade-off: the two copies are now *different
classes*. If dependency A and dependency B both expose, say, a `fipp` record
across their public API, and you've inlined two separate `fipp`s, an instance
from A's copy is not an instance of B's copy and they won't interoperate.
Nesting trades a version conflict for type-identity fragmentation, which is fine
when the conflicting library is an internal detail of each subtree and a problem
when it leaks across boundaries.

The cost is that the rename is *path-dependent*: the same source namespace maps
to different new names depending on which subtree it's in. So unresolved mode
can't use the global one-to-one map; it rewrites references per subtree as it
walks. Two extra knobs exist only here:

- **overrides** force a particular version at a particular path in the tree
  (normal "put a newer version at the top level" tricks don't work when the tree
  is unresolved).
- **expositions** opt a transitive dependency back into the project's own source
  rewriting. By default unresolved mode applies *transitive dependency hygiene*:
  it does not rewrite references to a transitive dependency in your own files,
  because your files shouldn't be reaching into someone else's transitive deps.

## The rewriting engine (`mranderson.move`)

`mranderson.move` started life as a copy of Stuart Sierra's
`clojure.tools.namespace.move` and has since grown well past it. Renaming a
namespace means rewriting two very different kinds of text, so the engine splits
every file into its `ns` form and its body and treats them separately.

### The ns form: structural

The `ns` form is parsed with [rewrite-clj](https://github.com/clj-commons/rewrite-clj)
and edited as a zipper. This is where the namespace gets its new name (and its
`^{:mranderson/inlined true}` watermark), and where `:require`/`:use`/`:import`
specs get rewritten. Doing this structurally matters because `ns` forms are
fiddly: metadata maps, prefix lists, reader conditionals for `.cljc`, and so on.
The platform-aware handling (`find-and-replace-platform-specific-subforms`) is
what lets a `.cljc` file that requires the same namespace under both `:clj` and
`:cljs` branches get each branch rewritten to the right place.

### The body: textual

The rest of the file is rewritten as text, with a regex that finds candidate
symbols and a replacement function (`source-replacement`) that decides what each
one should become. Text, not structure, because the body can contain anything,
and we only care about a narrow set of tokens: references to a moved namespace, a
fully-qualified class under one, a type hint, or a `(load "...")` resource path.
`source-replacement` has a branch per shape, and the comments there carry the
scars of specific bugs (dashes versus underscores in Java packages,
[#73](https://github.com/benedekfazekas/mranderson/issues/73); `load` paths,
[#61](https://github.com/benedekfazekas/mranderson/issues/61)).

The flip side of "it's just text" is the limit from the top of this doc: the
regex sees literal tokens, and nothing else. A namespace named by a runtime
string, built up and handed to `require`/`resolve`, or `read` out of a resource,
goes straight through untouched. That's not a bug to fix so much as the boundary
of the approach.

### One pass per file

In resolved mode every file is rewritten in a single pass: parse the `ns` form
once, scan the body once, and apply *all* the renames together
(`replace-ns-symbols`). Tokens are dispatched to the matching rename by their
first segment, so a file full of `clojure.core` calls and locals costs almost
nothing. This replaced an older design that re-scanned and re-parsed every file
once per moved namespace, which on a medium dependency tree meant parsing each
file around a dozen times. If you're tempted to "simplify" this back into a loop
over namespaces, run `lein test :benchmark` first and watch it get slow again.

### The Java/namespace overlap

The nastiest corner, and the source of a family of `ClassNotFoundException` bugs
([#33](https://github.com/benedekfazekas/mranderson/issues/33),
[#97](https://github.com/benedekfazekas/mranderson/issues/97)), is when a package
is *both* a Java package (it has `.class` files) and a Clojure namespace (it has
a `.clj` file). claypoole's `com.climate.claypoole.impl` is the textbook case.
That one package holds both:

- `PriorityThreadpoolImpl`, a compiled Java `.class`, and
- `PriorityThreadpool`, a deftype from the Clojure namespace of the same name.

The main namespace imports both.

These have to end up in different places: the Java class follows jarjar to the
name+version prefix, the deftype follows its namespace to the `:project-prefix`.
The engine keeps them straight by knowing which fully-qualified names correspond
to actual `.class` files:

- A mixed `(:import [pkg DeftypeClass JavaClass])` is *split* so each class gets
  the right package (`rewrite-import-spec`), and the match is done as a suffix so
  it still works after the namespace move has already prefixed the shared package.
- A fully-qualified Java class reference in a body (`(some.pkg.Widget. ...)`) is
  left untouched by the namespace move and prefixed by the Java pass instead,
  because the namespace move would otherwise send it to the wrong prefix.

In MrAnderson's source-rewrite model the inlined deftype/defrecord classes are
generated when the inlined source is compiled, so there's no bundled `.class` to
relocate and they move with their namespace. (A dependency that ships its own
AOT-compiled classes is just the bundled-`.class` case above: those go through
jarjar like any other Java class.)

## Watermarking

Every inlined namespace gets `^{:mranderson/inlined true}` metadata. This is how
downstream tools (cljdoc, for one) tell "this is a vendored copy, don't document
it" from the library's real API. The key is configurable via the `watermark`
option; set it to `nil` to turn it off.

## The Leiningen layer

Two thin namespaces wrap the engine for Leiningen users:

- `leiningen.inline-deps` is the `lein inline-deps` task. It reads options off
  the CLI and the project map and calls into `mranderson.core`.
- `mranderson.plugin` is Leiningen middleware that injects the
  `plugin.mranderson/config` profile (defined as data in `mranderson.profiles`),
  which is what puts `target/srcdeps` on the classpath so you can `repl`/`test`/
  `install` against the inlined build.

None of this is required. `mranderson.core/inline-deps` is a plain data-in entry
point that does the same work without Leiningen, which is what tools.build and
Clojure CLI users call.

## A note on self-hosting

MrAnderson inlines its *own* dependencies (pomegranate, tools.namespace, fs,
rewrite-clj) using itself. That makes the build a small bootstrap puzzle, which
the `Makefile` handles. See [CONTRIBUTING](../CONTRIBUTING.md) for the details.
It also means MrAnderson is its own best integration test: if a change breaks the
engine, `make install` tends to fall over.

## Where to look

| You want to change...                         | Start in |
|-----------------------------------------------|----------|
| The pipeline / orchestration                  | `mranderson.core` |
| Namespace and reference rewriting             | `mranderson.move` |
| Java class repackaging / jarjar               | `mranderson.util`, `java-src/mranderson/util/` |
| Dependency resolution                         | `mranderson.dependency.resolver` |
| Tree walking and ordering                     | `mranderson.dependency.tree` |
| The `lein inline-deps` task / options         | `leiningen.inline-deps`, `mranderson.plugin` |
| Performance                                   | `mranderson.benchmark` (run with `lein test :benchmark`) |
