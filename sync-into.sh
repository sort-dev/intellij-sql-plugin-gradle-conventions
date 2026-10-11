#!/usr/bin/env bash
set -euo pipefail

plugin=${1:?usage: sync-into.sh <plugin-dir> [<commit>]}
here=$(cd "$(dirname "$0")" && pwd)
commit=$(git -C "$here" rev-parse --verify "${2:-HEAD}^{commit}")
tree=$(git -C "$here" rev-parse "$commit:build-logic")
target="$plugin/build-logic"

[[ -f "$plugin/settings.gradle.kts" ]] || { echo "Not a Gradle build: $plugin" >&2; exit 1; }

rm -rf "$target"
mkdir -p "$target"
git -C "$here" archive "$commit" build-logic | tar -x -C "$target" --strip-components=1
printf 'repository %s\ncommit %s\ntree %s\n' \
    https://github.com/sort-dev/intellij-sql-plugin-gradle-conventions "$commit" "$tree" > "$target/VENDORED-FROM"
echo "Synced build-logic at $commit into $target"
