#!/usr/bin/env bash
set -euo pipefail

usage="usage: sync-into.sh [--force] <plugin-dir> [<commit>]"
force=false
if [[ ${1:-} == --force ]]; then force=true; shift; fi
plugin=${1:?$usage}
here=$(cd "$(dirname "$0")" && pwd)
commit=$(git -C "$here" rev-parse --verify "${2:-HEAD}^{commit}")
tree=$(git -C "$here" rev-parse "$commit:build-logic")
target="$plugin/build-logic"

[[ -f "$plugin/settings.gradle.kts" ]] || { echo "Not a Gradle build: $plugin" >&2; exit 1; }

if [[ -e "$target" && $force == false ]]; then
    vendored=$(sed -n 's/^commit //p' "$target/VENDORED-FROM" 2>/dev/null || true)
    if [[ -z $vendored ]] || ! git -C "$here" cat-file -e "$vendored^{commit}" 2>/dev/null; then
        echo "$target has no known VENDORED-FROM commit; rerun with --force to replace it" >&2
        exit 1
    fi
    pristine=$(mktemp -d)
    trap 'rm -rf "$pristine"' EXIT
    git -C "$here" archive "$vendored" build-logic | tar -x -C "$pristine" --strip-components=1
    if ! diff -rq -x VENDORED-FROM -x build -x local.properties -x '.*' -x '*.orig' "$pristine" "$target" >&2; then
        echo "$target has local changes since $vendored; rerun with --force to replace them" >&2
        exit 1
    fi
fi

rm -rf "$target"
mkdir -p "$target"
git -C "$here" archive "$commit" build-logic | tar -x -C "$target" --strip-components=1
printf 'repository %s\ncommit %s\ntree %s\n' \
    https://github.com/sort-dev/intellij-sql-plugin-gradle-conventions "$commit" "$tree" > "$target/VENDORED-FROM"
echo "Synced build-logic at $commit into $target"
