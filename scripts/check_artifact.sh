#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(git rev-parse --show-toplevel)"

# Assert that the inlined artifact ships only MrAnderson's own code: the inlined
# Clojure dependencies under mranderson/inlined, the lein task and the three
# jarjar helper classes. Anything else - a third-party class file or resource at
# its original path - would land unshaded on every consumer's classpath and
# clash with their own copy (see #134, where bundled aether classes broke
# builds that also had tools.deps on the classpath).

jars=(target/mranderson-*.jar)
jar="${jars[0]}"
if [ ! -f "$jar" ]; then
  echo "no target/mranderson-*.jar found; run make install first" >&2
  exit 2
fi

entries="$(unzip -Z1 "$jar" | grep -v '/$')"
leaked="$(grep -v -E '^(mranderson/|leiningen/|META-INF/)' <<<"$entries" || true)"
classes="$(grep -E '\.class$' <<<"$entries" | grep -v '^mranderson/util/' || true)"

if [ -n "$leaked$classes" ]; then
  echo "third-party files leaked into $jar:" >&2
  printf '%s\n' "$leaked" "$classes" | sed '/^$/d' >&2
  exit 1
fi
echo "$jar ships only MrAnderson's own files"
