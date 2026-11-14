#!/usr/bin/env bash
# Demo: format the messy sample projects in a scratch copy and show the diff.
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=$(ls cli/build/libs/vl-format-*.jar 2>/dev/null | head -1 || true)
if [ -z "$JAR" ]; then
    ./gradlew :cli:fatJar -q
    JAR=$(ls cli/build/libs/vl-format-*.jar | head -1)
fi

SCRATCH=$(mktemp -d /tmp/vl-format-demo.XXXXXX)
cp -R samples/messy-kotlin samples/messy-java "$SCRATCH/"

echo "== check (exit 2 = needs formatting) =="
java -jar "$JAR" --check "$SCRATCH" && echo "already clean" || echo "exit=$?"

echo
echo "== format =="
java -jar "$JAR" --stats "$SCRATCH"

echo
echo "== diff (before -> after) =="
diff -ru samples/messy-kotlin "$SCRATCH/messy-kotlin" || true
diff -ru samples/messy-java "$SCRATCH/messy-java" || true

echo
echo "== second run is a no-op =="
java -jar "$JAR" --check --stats "$SCRATCH" && echo "clean ✓"

rm -rf "$SCRATCH"
